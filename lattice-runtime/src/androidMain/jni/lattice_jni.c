/**
 * JNI bridge between Kotlin/Android (and Host JVM) and the Lattice C API (v1.1.0 / 3.1.2).
 *
 * Implements all native methods declared in androidMain NativeBridgeImpl.kt.
 */

#include <jni.h>
#include <stdio.h>
#include <string.h>
#include <stdlib.h>
#include <pthread.h>
#ifdef __ANDROID__
#include <android/log.h>
#define LATTICE_LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, "LatticeJNI", __VA_ARGS__)
#define LATTICE_LOGI(...) __android_log_print(ANDROID_LOG_INFO,  "LatticeJNI", __VA_ARGS__)
#define LATTICE_LOGW(...) __android_log_print(ANDROID_LOG_WARN,  "LatticeJNI", __VA_ARGS__)
#define LATTICE_LOGE(...) __android_log_print(ANDROID_LOG_ERROR, "LatticeJNI", __VA_ARGS__)
#else
#define LATTICE_LOGD(...) do { } while (0)
#define LATTICE_LOGI(...) do { } while (0)
#define LATTICE_LOGW(...) do { } while (0)
#define LATTICE_LOGE(...) do { fprintf(stderr, "[LatticeJNI ERROR] " __VA_ARGS__); fprintf(stderr, "\n"); } while (0)
#endif

#include "lattice.h"

#define JNI_FN(name) Java_com_lattice_NativeBridge_##name

// Schema flag bits (mirrored in LatticeNative.kt)
#define FLAG_UNIQUE        1
#define FLAG_INDEXED       2
#define FLAG_FULL_TEXT     4
#define FLAG_VECTOR        8
#define FLAG_NO_HISTORY    16
#define FLAG_GEO_BOUNDS    32
#define FLAG_ALLOWS_UPSERT 64

static JavaVM* g_jvm = NULL;

JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM* vm, void* reserved) {
    (void)reserved;
    g_jvm = vm;
    return JNI_VERSION_1_6;
}

typedef struct {
    jobject callback;
    jmethodID invokeMethod;
    uint64_t token;
} observer_context_t;

static char* jstring_to_cstring(JNIEnv* env, jstring str) {
    if (str == NULL) return NULL;
    const char* utf = (*env)->GetStringUTFChars(env, str, NULL);
    if (utf == NULL) return NULL;
    char* result = strdup(utf);
    (*env)->ReleaseStringUTFChars(env, str, utf);
    return result;
}

static jstring cstring_to_jstring(JNIEnv* env, const char* str) {
    if (str == NULL) return NULL;
    return (*env)->NewStringUTF(env, str);
}

static JNIEnv* attach_jvm_thread(int* out_needs_detach) {
    *out_needs_detach = 0;
    if (g_jvm == NULL) return NULL;
    JNIEnv* env = NULL;
    int status = (*g_jvm)->GetEnv(g_jvm, (void**)&env, JNI_VERSION_1_6);
    if (status == JNI_EDETACHED) {
        if ((*g_jvm)->AttachCurrentThread(g_jvm, &env, NULL) != 0) {
            return NULL;
        }
        *out_needs_detach = 1;
    } else if (status != JNI_OK) {
        return NULL;
    }
    return env;
}

static void detach_jvm_thread(int needs_detach) {
    if (needs_detach && g_jvm != NULL) {
        (*g_jvm)->DetachCurrentThread(g_jvm);
    }
}

// =============================================================================
// Delimited String Splitter (preserves empty tokens, unlike strtok!)
// =============================================================================

static size_t split_by_char(const char* input, char delim, char*** out_parts) {
    if (input == NULL) {
        *out_parts = NULL;
        return 0;
    }
    size_t count = 1;
    for (const char* p = input; *p != '\0'; p++) {
        if (*p == delim) count++;
    }
    char** parts = (char**)calloc(count, sizeof(char*));
    if (!parts) {
        *out_parts = NULL;
        return 0;
    }
    size_t idx = 0;
    const char* start = input;
    for (const char* p = input; ; p++) {
        if (*p == delim || *p == '\0') {
            size_t len = (size_t)(p - start);
            char* item = (char*)malloc(len + 1);
            if (item) {
                if (len > 0) memcpy(item, start, len);
                item[len] = '\0';
            }
            parts[idx++] = item;
            if (*p == '\0') break;
            start = p + 1;
        }
    }
    *out_parts = parts;
    return count;
}

static void free_split_parts(char** parts, size_t count) {
    if (!parts) return;
    for (size_t i = 0; i < count; i++) {
        free(parts[i]);
    }
    free(parts);
}

// =============================================================================
// Union Descriptor Parsing (`_UnionTable|case1;case2~param^typeOrd^isLink^linkTarget~...`)
// =============================================================================

static lattice_union_descriptor_t* parse_union_descriptor(const char* spec) {
    if (spec == NULL || spec[0] == '\0') return NULL;
    const char* bar = strchr(spec, '|');
    if (bar == NULL || bar == spec) return NULL;

    lattice_union_descriptor_t* desc = (lattice_union_descriptor_t*)calloc(1, sizeof(lattice_union_descriptor_t));
    if (!desc) return NULL;
    lattice_union_descriptor_init(desc);

    size_t tn_len = (size_t)(bar - spec);
    char* tn = (char*)malloc(tn_len + 1);
    memcpy(tn, spec, tn_len);
    tn[tn_len] = '\0';
    desc->union_table_name = tn;

    const char* cases_str = bar + 1;
    if (cases_str[0] == '\0') {
        return desc;
    }

    char** case_tokens = NULL;
    size_t raw_case_count = split_by_char(cases_str, ';', &case_tokens);
    size_t valid_cases = 0;
    for (size_t i = 0; i < raw_case_count; i++) {
        if (case_tokens[i] && case_tokens[i][0] != '\0') valid_cases++;
    }

    if (valid_cases > 0) {
        lattice_union_case_t* cases = (lattice_union_case_t*)calloc(valid_cases, sizeof(lattice_union_case_t));
        size_t ci = 0;
        for (size_t i = 0; i < raw_case_count; i++) {
            const char* ctok = case_tokens[i];
            if (!ctok || ctok[0] == '\0') continue;

            lattice_union_case_init(&cases[ci]);
            char** segs = NULL;
            size_t seg_count = split_by_char(ctok, '~', &segs);
            if (seg_count > 0 && segs[0]) {
                cases[ci].case_name = strdup(segs[0]);
            } else {
                cases[ci].case_name = strdup("");
            }

            size_t val_count = 0;
            for (size_t s = 1; s < seg_count; s++) {
                if (segs[s] && segs[s][0] != '\0') val_count++;
            }

            if (val_count > 0) {
                lattice_union_case_value_t* vals =
                    (lattice_union_case_value_t*)calloc(val_count, sizeof(lattice_union_case_value_t));
                size_t vi = 0;
                for (size_t s = 1; s < seg_count; s++) {
                    if (!segs[s] || segs[s][0] == '\0') continue;
                    lattice_union_case_value_init(&vals[vi]);
                    char** vp = NULL;
                    size_t vp_count = split_by_char(segs[s], '^', &vp);
                    vals[vi].param_name = strdup((vp_count > 0 && vp[0]) ? vp[0] : "");
                    int type_ord = (vp_count > 1 && vp[1] && vp[1][0] != '\0') ? atoi(vp[1]) : 2;
                    vals[vi].type = (lattice_column_type_t)type_ord;
                    vals[vi].is_link = (vp_count > 2 && vp[2] && strcmp(vp[2], "1") == 0);
                    vals[vi].link_target = (vp_count > 3 && vp[3] && vp[3][0] != '\0') ? strdup(vp[3]) : NULL;
                    free_split_parts(vp, vp_count);
                    vi++;
                }
                cases[ci].values = vals;
                cases[ci].value_count = val_count;
            }

            free_split_parts(segs, seg_count);
            ci++;
        }
        desc->cases = cases;
        desc->case_count = valid_cases;
    }

    free_split_parts(case_tokens, raw_case_count);
    return desc;
}

static void free_union_descriptor(lattice_union_descriptor_t* desc) {
    if (!desc) return;
    free((void*)desc->union_table_name);
    if (desc->cases) {
        for (size_t ci = 0; ci < desc->case_count; ci++) {
            const lattice_union_case_t* c = &desc->cases[ci];
            free((void*)c->case_name);
            if (c->values) {
                for (size_t vi = 0; vi < c->value_count; vi++) {
                    free((void*)c->values[vi].param_name);
                    free((void*)c->values[vi].link_target);
                }
                free((void*)c->values);
            }
        }
        free((void*)desc->cases);
    }
    free(desc);
}

// =============================================================================
// Database Lifecycle & Unified Open v2
// =============================================================================

JNIEXPORT jlong JNICALL JNI_FN(nativeCreateDbInMemory)(JNIEnv* env, jobject thiz) {
    (void)env; (void)thiz;
    lattice_db_t* db = lattice_db_create_in_memory();
    return (jlong)(intptr_t)db;
}

JNIEXPORT jlong JNICALL JNI_FN(nativeCreateDbAtPath)(JNIEnv* env, jobject thiz, jstring path) {
    (void)thiz;
    char* c_path = jstring_to_cstring(env, path);
    lattice_db_t* db = lattice_db_create_at_path(c_path);
    free(c_path);
    return (jlong)(intptr_t)db;
}

JNIEXPORT jlong JNICALL JNI_FN(nativeCreateDbWithSchemas)(JNIEnv* env, jobject thiz,
                                                          jstring path, jstring schemasJson) {
    (void)thiz; (void)schemasJson;
    char* c_path = jstring_to_cstring(env, path);
    lattice_db_t* db;
    if (c_path == NULL || strcmp(c_path, ":memory:") == 0) {
        db = lattice_db_create_in_memory();
    } else {
        db = lattice_db_create_at_path(c_path);
    }
    free(c_path);
    return (jlong)(intptr_t)db;
}

JNIEXPORT jlong JNICALL JNI_FN(nativeCreateDbWithSync)(JNIEnv* env, jobject thiz,
                                                        jstring path, jstring schemasJson,
                                                        jstring syncEndpoint, jstring authToken) {
    (void)thiz; (void)schemasJson;
    char* c_path = jstring_to_cstring(env, path);
    char* c_endpoint = jstring_to_cstring(env, syncEndpoint);
    char* c_token = jstring_to_cstring(env, authToken);

    lattice_db_t* db = lattice_db_create_with_sync(c_path, NULL, 0, NULL, c_endpoint, c_token);

    free(c_path);
    free(c_endpoint);
    free(c_token);
    return (jlong)(intptr_t)db;
}

typedef struct {
    jobject fn_global;
    jmethodID invoke_method;
} jni_migration_ctx_t;

static void jni_migration_table_row_callback(
    void* context,
    const char* table_name,
    const char* old_values_json,
    char** new_values_json
) {
    if (context == NULL || table_name == NULL || old_values_json == NULL || new_values_json == NULL) return;
    jni_migration_ctx_t* mctx = (jni_migration_ctx_t*)context;
    int needs_detach = 0;
    JNIEnv* env = attach_jvm_thread(&needs_detach);
    if (env == NULL) return;

    jstring j_table = cstring_to_jstring(env, table_name);
    jstring j_old = cstring_to_jstring(env, old_values_json);
    jstring j_res = (jstring)(*env)->CallObjectMethod(env, mctx->fn_global, mctx->invoke_method, j_table, j_old);

    if ((*env)->ExceptionCheck(env)) {
        (*env)->ExceptionClear(env);
        j_res = NULL;
    }
    if (j_res != NULL) {
        *new_values_json = jstring_to_cstring(env, j_res);
        (*env)->DeleteLocalRef(env, j_res);
    }
    if (j_table) (*env)->DeleteLocalRef(env, j_table);
    if (j_old) (*env)->DeleteLocalRef(env, j_old);
    detach_jvm_thread(needs_detach);
}

JNIEXPORT jlong JNICALL JNI_FN(nativeOpenDbV2)(
    JNIEnv* env,
    jobject thiz,
    jstring path,
    jobjectArray tableNames,
    jintArray propertyCounts,
    jobjectArray propNames,
    jobjectArray propColumnNames,
    jintArray propTypes,
    jintArray propKinds,
    jbooleanArray propNullable,
    jobjectArray propTargetTables,
    jobjectArray propLinkTables,
    jintArray propFlags,
    jobjectArray propUnionSpecs,
    jobjectArray constraintTableNames,
    jobjectArray constraintColumnsJoined,
    jbooleanArray constraintAllowsUpsert,
    jlong schedulerHandle,
    jstring syncEndpoint,
    jstring authToken,
    jint targetSchemaVersion,
    jint busyTimeoutMs,
    jlong auditRetentionSeconds,
    jint syncIsObserver,
    jint uploadsEnabled,
    jint syncAuthMode,
    jstring websocketSyncIdentity,
    jstring pinnedServerCertSha256,
    jint createIfMissing,
    jint readOnly,
    jstring syncFilterJson,
    jlong chunkSize,
    jint maxReconnectAttempts,
    jint uploadCoalesceMs,
    jobjectArray migrationTables,
    jobject migrationTransform
) {
    (void)thiz;
    char* c_path = jstring_to_cstring(env, path);
    char* c_endpoint = jstring_to_cstring(env, syncEndpoint);
    char* c_token = jstring_to_cstring(env, authToken);
    char* c_ws_id = jstring_to_cstring(env, websocketSyncIdentity);
    char* c_cert_pin = jstring_to_cstring(env, pinnedServerCertSha256);
    char* c_sync_filter = jstring_to_cstring(env, syncFilterJson);

    jsize num_tables = tableNames ? (*env)->GetArrayLength(env, tableNames) : 0;
    jsize total_props = propNames ? (*env)->GetArrayLength(env, propNames) : 0;
    jsize num_constraints = constraintTableNames ? (*env)->GetArrayLength(env, constraintTableNames) : 0;

    jint* p_counts = (num_tables > 0 && propertyCounts) ? (*env)->GetIntArrayElements(env, propertyCounts, NULL) : NULL;
    jint* p_types = (total_props > 0 && propTypes) ? (*env)->GetIntArrayElements(env, propTypes, NULL) : NULL;
    jint* p_kinds = (total_props > 0 && propKinds) ? (*env)->GetIntArrayElements(env, propKinds, NULL) : NULL;
    jboolean* p_nullable = (total_props > 0 && propNullable) ? (*env)->GetBooleanArrayElements(env, propNullable, NULL) : NULL;
    jint* p_flags = (total_props > 0 && propFlags) ? (*env)->GetIntArrayElements(env, propFlags, NULL) : NULL;
    jboolean* c_upserts = (num_constraints > 0 && constraintAllowsUpsert)
        ? (*env)->GetBooleanArrayElements(env, constraintAllowsUpsert, NULL) : NULL;

    // Pre-extract constraint table names & columns so we can group by table cleanly
    char** c_table_names = (num_constraints > 0) ? (char**)calloc((size_t)num_constraints, sizeof(char*)) : NULL;
    char** c_cols_joined = (num_constraints > 0) ? (char**)calloc((size_t)num_constraints, sizeof(char*)) : NULL;
    for (jsize ci = 0; ci < num_constraints; ci++) {
        jstring j_ctn = (jstring)(*env)->GetObjectArrayElement(env, constraintTableNames, ci);
        jstring j_ccj = (jstring)(*env)->GetObjectArrayElement(env, constraintColumnsJoined, ci);
        c_table_names[ci] = jstring_to_cstring(env, j_ctn);
        c_cols_joined[ci] = jstring_to_cstring(env, j_ccj);
        if (j_ctn) (*env)->DeleteLocalRef(env, j_ctn);
        if (j_ccj) (*env)->DeleteLocalRef(env, j_ccj);
    }

    lattice_schema_ext_t* schemas = (num_tables > 0)
        ? (lattice_schema_ext_t*)calloc((size_t)num_tables, sizeof(lattice_schema_ext_t))
        : NULL;

    jsize prop_idx = 0;
    for (jsize t = 0; t < num_tables; t++) {
        lattice_schema_ext_init(&schemas[t]);
        jstring j_table = (jstring)(*env)->GetObjectArrayElement(env, tableNames, t);
        char* tname = jstring_to_cstring(env, j_table);
        if (j_table) (*env)->DeleteLocalRef(env, j_table);
        schemas[t].table_name = tname;

        jint num_props = p_counts ? p_counts[t] : 0;
        schemas[t].property_count = (size_t)num_props;

        if (num_props > 0) {
            lattice_property_ext_t* props =
                (lattice_property_ext_t*)calloc((size_t)num_props, sizeof(lattice_property_ext_t));
            schemas[t].properties = props;

            for (jint p = 0; p < num_props && prop_idx < total_props; p++, prop_idx++) {
                lattice_property_ext_init(&props[p]);
                int flags = p_flags ? p_flags[prop_idx] : 0;

                jstring j_name = (jstring)(*env)->GetObjectArrayElement(env, propNames, prop_idx);
                props[p].base.name = jstring_to_cstring(env, j_name);
                if (j_name) (*env)->DeleteLocalRef(env, j_name);

                if (propColumnNames != NULL) {
                    jstring j_col = (jstring)(*env)->GetObjectArrayElement(env, propColumnNames, prop_idx);
                    if (j_col != NULL) {
                        props[p].base.column_name = jstring_to_cstring(env, j_col);
                        (*env)->DeleteLocalRef(env, j_col);
                    }
                }

                props[p].base.type = (lattice_column_type_t)(p_types ? p_types[prop_idx] : 0);
                props[p].base.kind = (lattice_property_kind_t)(p_kinds ? p_kinds[prop_idx] : 0);
                props[p].base.nullable = p_nullable ? (p_nullable[prop_idx] != JNI_FALSE) : false;

                if (propTargetTables != NULL) {
                    jstring j_target = (jstring)(*env)->GetObjectArrayElement(env, propTargetTables, prop_idx);
                    if (j_target != NULL) {
                        props[p].base.target_table = jstring_to_cstring(env, j_target);
                        (*env)->DeleteLocalRef(env, j_target);
                    }
                }
                if (propLinkTables != NULL) {
                    jstring j_link = (jstring)(*env)->GetObjectArrayElement(env, propLinkTables, prop_idx);
                    if (j_link != NULL) {
                        props[p].base.link_table = jstring_to_cstring(env, j_link);
                        (*env)->DeleteLocalRef(env, j_link);
                    }
                }

                props[p].base.is_unique     = (flags & FLAG_UNIQUE) != 0;
                props[p].base.is_indexed    = (flags & FLAG_INDEXED) != 0;
                props[p].base.is_full_text  = (flags & FLAG_FULL_TEXT) != 0;
                props[p].base.is_vector     = (flags & FLAG_VECTOR) != 0;
                props[p].base.is_geo_bounds = (flags & FLAG_GEO_BOUNDS) != 0;
                props[p].no_history         = (flags & FLAG_NO_HISTORY) != 0;

                if (propUnionSpecs != NULL) {
                    jstring j_uspec = (jstring)(*env)->GetObjectArrayElement(env, propUnionSpecs, prop_idx);
                    if (j_uspec != NULL) {
                        char* c_uspec = jstring_to_cstring(env, j_uspec);
                        props[p].union_desc = parse_union_descriptor(c_uspec);
                        free(c_uspec);
                        (*env)->DeleteLocalRef(env, j_uspec);
                    }
                }
            }
        }

        // Attach compound / allows_upsert unique constraints for this table
        size_t matching_constraints = 0;
        for (jsize ci = 0; ci < num_constraints; ci++) {
            if (c_table_names[ci] && tname && strcmp(c_table_names[ci], tname) == 0) {
                matching_constraints++;
            }
        }
        if (matching_constraints > 0) {
            lattice_unique_constraint_t* t_cons =
                (lattice_unique_constraint_t*)calloc(matching_constraints, sizeof(lattice_unique_constraint_t));
            size_t m_idx = 0;
            for (jsize ci = 0; ci < num_constraints; ci++) {
                if (c_table_names[ci] && tname && strcmp(c_table_names[ci], tname) == 0) {
                    char** cols = NULL;
                    size_t col_count = split_by_char(c_cols_joined[ci] ? c_cols_joined[ci] : "", ',', &cols);
                    size_t non_empty = 0;
                    for (size_t k = 0; k < col_count; k++) {
                        if (cols[k] && cols[k][0] != '\0') non_empty++;
                    }
                    const char** prop_names_arr = non_empty > 0
                        ? (const char**)calloc(non_empty, sizeof(const char*)) : NULL;
                    size_t out_k = 0;
                    for (size_t k = 0; k < col_count; k++) {
                        if (cols[k] && cols[k][0] != '\0') {
                            prop_names_arr[out_k++] = strdup(cols[k]);
                        }
                    }
                    free_split_parts(cols, col_count);

                    t_cons[m_idx].table_name = strdup(tname);
                    t_cons[m_idx].property_names = prop_names_arr;
                    t_cons[m_idx].property_count = non_empty;
                    t_cons[m_idx].allows_upsert = c_upserts ? (c_upserts[ci] != JNI_FALSE) : false;
                    m_idx++;
                }
            }
            schemas[t].constraints = t_cons;
            schemas[t].constraint_count = matching_constraints;
        }
    }

    if (p_counts) (*env)->ReleaseIntArrayElements(env, propertyCounts, p_counts, JNI_ABORT);
    if (p_types) (*env)->ReleaseIntArrayElements(env, propTypes, p_types, JNI_ABORT);
    if (p_kinds) (*env)->ReleaseIntArrayElements(env, propKinds, p_kinds, JNI_ABORT);
    if (p_nullable) (*env)->ReleaseBooleanArrayElements(env, propNullable, p_nullable, JNI_ABORT);
    if (p_flags) (*env)->ReleaseIntArrayElements(env, propFlags, p_flags, JNI_ABORT);
    if (c_upserts) (*env)->ReleaseBooleanArrayElements(env, constraintAllowsUpsert, c_upserts, JNI_ABORT);

    lattice_sync_options_t opts;
    lattice_sync_options_init(&opts);
    if (targetSchemaVersion >= 1) opts.target_schema_version = (int32_t)targetSchemaVersion;
    if (busyTimeoutMs > 0) opts.busy_timeout_ms = (int32_t)busyTimeoutMs;
    if (auditRetentionSeconds >= 0) opts.audit_retention_seconds = (int64_t)auditRetentionSeconds;
    if (syncIsObserver >= 0) opts.sync_is_observer = (int32_t)syncIsObserver;
    if (uploadsEnabled >= 0) opts.uploads_enabled = (int32_t)uploadsEnabled;
    if (syncAuthMode >= 0) opts.sync_authentication_mode = (int32_t)syncAuthMode;
    if (c_ws_id != NULL) opts.websocket_sync_identity = c_ws_id;
    if (c_cert_pin != NULL) opts.pinned_server_certificate_sha256 = c_cert_pin;
    if (createIfMissing >= 0) opts.create_if_missing = (int32_t)createIfMissing;
    if (readOnly >= 0) opts.read_only = (int32_t)readOnly;
    if (c_sync_filter != NULL) opts.sync_filter_json = c_sync_filter;
    if (chunkSize > 0) opts.chunk_size = (int64_t)chunkSize;
    if (maxReconnectAttempts >= 0) opts.max_reconnect_attempts = (int32_t)maxReconnectAttempts;
    if (uploadCoalesceMs >= 0) opts.upload_coalesce_ms = (int32_t)uploadCoalesceMs;

    jsize num_mig_tables = (migrationTables && migrationTransform)
        ? (*env)->GetArrayLength(env, migrationTables) : 0;
    char** c_mig_tables = NULL;
    jni_migration_ctx_t mig_ctx = { NULL, NULL };
    lattice_migration_table_row_fn mig_fn = NULL;

    if (num_mig_tables > 0 && migrationTransform != NULL) {
        jclass fnClass = (*env)->GetObjectClass(env, migrationTransform);
        jmethodID invMethod = (*env)->GetMethodID(
            env, fnClass, "invoke", "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;"
        );
        (*env)->DeleteLocalRef(env, fnClass);
        if (invMethod != NULL) {
            mig_ctx.fn_global = (*env)->NewGlobalRef(env, migrationTransform);
            mig_ctx.invoke_method = invMethod;
            mig_fn = jni_migration_table_row_callback;

            c_mig_tables = (char**)calloc((size_t)num_mig_tables, sizeof(char*));
            for (jsize mi = 0; mi < num_mig_tables; mi++) {
                jstring j_mt = (jstring)(*env)->GetObjectArrayElement(env, migrationTables, mi);
                c_mig_tables[mi] = jstring_to_cstring(env, j_mt);
                if (j_mt) (*env)->DeleteLocalRef(env, j_mt);
            }
        }
    }

    lattice_scheduler_t* sched = (schedulerHandle != 0)
        ? (lattice_scheduler_t*)(intptr_t)schedulerHandle : NULL;

    lattice_db_t* db = lattice_db_open_v2(
        c_path,
        schemas,
        (size_t)num_tables,
        sizeof(lattice_schema_ext_t),
        sched,
        c_endpoint,
        c_token,
        &opts,
        (const char* const*)c_mig_tables,
        c_mig_tables ? (size_t)num_mig_tables : 0,
        mig_fn ? &mig_ctx : NULL,
        mig_fn
    );

    if (mig_ctx.fn_global != NULL) {
        (*env)->DeleteGlobalRef(env, mig_ctx.fn_global);
    }
    if (c_mig_tables != NULL) {
        for (jsize mi = 0; mi < num_mig_tables; mi++) free(c_mig_tables[mi]);
        free(c_mig_tables);
    }

    // Cleanup schema allocations
    for (jsize t = 0; t < num_tables; t++) {
        free((void*)schemas[t].table_name);
        if (schemas[t].properties) {
            for (size_t p = 0; p < schemas[t].property_count; p++) {
                const lattice_property_ext_t* pe = &schemas[t].properties[p];
                free((void*)pe->base.name);
                free((void*)pe->base.column_name);
                free((void*)pe->base.target_table);
                free((void*)pe->base.link_table);
                free_union_descriptor((lattice_union_descriptor_t*)pe->union_desc);
            }
            free((void*)schemas[t].properties);
        }
        if (schemas[t].constraints) {
            for (size_t ci = 0; ci < schemas[t].constraint_count; ci++) {
                const lattice_unique_constraint_t* uc = &schemas[t].constraints[ci];
                free((void*)uc->table_name);
                if (uc->property_names) {
                    for (size_t k = 0; k < uc->property_count; k++) {
                        free((void*)uc->property_names[k]);
                    }
                    free((void*)uc->property_names);
                }
            }
            free((void*)schemas[t].constraints);
        }
    }
    free(schemas);

    if (c_table_names) {
        for (jsize ci = 0; ci < num_constraints; ci++) free(c_table_names[ci]);
        free(c_table_names);
    }
    if (c_cols_joined) {
        for (jsize ci = 0; ci < num_constraints; ci++) free(c_cols_joined[ci]);
        free(c_cols_joined);
    }

    free(c_path);
    free(c_endpoint);
    free(c_token);
    free(c_ws_id);
    free(c_cert_pin);
    free(c_sync_filter);

    return (jlong)(intptr_t)db;
}

JNIEXPORT jlong JNICALL JNI_FN(nativeCreateDbWithSchemaArrays)(
    JNIEnv* env,
    jobject thiz,
    jstring path,
    jobjectArray tableNames,
    jintArray propertyCounts,
    jobjectArray propNames,
    jintArray propTypes,
    jintArray propKinds,
    jbooleanArray propNullable,
    jobjectArray propTargetTables,
    jobjectArray propLinkTables,
    jintArray propFlags,
    jstring syncEndpoint,
    jstring authToken
) {
    return JNI_FN(nativeOpenDbV2)(
        env, thiz, path, tableNames, propertyCounts, propNames, NULL,
        propTypes, propKinds, propNullable, propTargetTables, propLinkTables,
        propFlags, NULL, NULL, NULL, NULL, 0, syncEndpoint, authToken,
        1, -1, -1, -1, -1, -1, NULL, NULL, -1, -1, NULL, 0, -1, -1, NULL, NULL
    );
}

JNIEXPORT void JNICALL JNI_FN(nativeCloseDb)(JNIEnv* env, jobject thiz, jlong dbHandle) {
    (void)env; (void)thiz;
    lattice_db_t* db = (lattice_db_t*)(intptr_t)dbHandle;
    if (db != NULL) lattice_db_close(db);
}

JNIEXPORT void JNICALL JNI_FN(nativeReleaseDb)(JNIEnv* env, jobject thiz, jlong dbHandle) {
    (void)env; (void)thiz;
    lattice_db_t* db = (lattice_db_t*)(intptr_t)dbHandle;
    if (db != NULL) lattice_db_release(db);
}

JNIEXPORT jstring JNICALL JNI_FN(nativeGetLastError)(JNIEnv* env, jobject thiz) {
    (void)thiz;
    return cstring_to_jstring(env, lattice_last_error());
}

// =============================================================================
// Object Operations
// =============================================================================

JNIEXPORT void JNICALL JNI_FN(nativeReleaseObject)(JNIEnv* env, jobject thiz, jlong objHandle) {
    (void)env; (void)thiz;
    lattice_object_t* obj = (lattice_object_t*)(intptr_t)objHandle;
    if (obj != NULL) lattice_object_release(obj);
}

JNIEXPORT jlong JNICALL JNI_FN(nativeAddObject)(JNIEnv* env, jobject thiz,
                                                 jlong dbHandle, jlong objectHandle) {
    (void)env; (void)thiz;
    lattice_db_t* db = (lattice_db_t*)(intptr_t)dbHandle;
    lattice_object_t* obj = (lattice_object_t*)(intptr_t)objectHandle;
    if (db == NULL || obj == NULL) return 0;
    return (jlong)(intptr_t)lattice_db_add(db, obj);
}

JNIEXPORT jlong JNICALL JNI_FN(nativeAddObjectWithGlobalId)(JNIEnv* env, jobject thiz,
                                                             jlong dbHandle, jlong objectHandle,
                                                             jstring globalId) {
    (void)thiz;
    lattice_db_t* db = (lattice_db_t*)(intptr_t)dbHandle;
    lattice_object_t* obj = (lattice_object_t*)(intptr_t)objectHandle;
    if (db == NULL || obj == NULL) return 0;
    char* c_gid = jstring_to_cstring(env, globalId);
    lattice_object_t* res = lattice_db_add_with_global_id(db, obj, c_gid);
    free(c_gid);
    return (jlong)(intptr_t)res;
}

JNIEXPORT jlong JNICALL JNI_FN(nativeCreateDbObject)(JNIEnv* env, jobject thiz,
                                                      jlong dbHandle, jstring tableName) {
    (void)thiz;
    lattice_db_t* db = (lattice_db_t*)(intptr_t)dbHandle;
    if (db == NULL) return 0;
    char* c_table = jstring_to_cstring(env, tableName);
    lattice_object_t* obj = lattice_db_create_object(db, c_table);
    free(c_table);
    return (jlong)(intptr_t)obj;
}

JNIEXPORT jlong JNICALL JNI_FN(nativeFindObject)(JNIEnv* env, jobject thiz,
                                                  jlong dbHandle, jstring tableName, jlong id) {
    (void)thiz;
    lattice_db_t* db = (lattice_db_t*)(intptr_t)dbHandle;
    if (db == NULL) return 0;
    char* c_table = jstring_to_cstring(env, tableName);
    lattice_object_t* obj = lattice_db_find(db, c_table, id);
    free(c_table);
    return (jlong)(intptr_t)obj;
}

JNIEXPORT jlong JNICALL JNI_FN(nativeFindObjectByGlobalId)(JNIEnv* env, jobject thiz,
                                                           jlong dbHandle, jstring tableName,
                                                           jstring globalId) {
    (void)thiz;
    lattice_db_t* db = (lattice_db_t*)(intptr_t)dbHandle;
    if (db == NULL) return 0;
    char* c_table = jstring_to_cstring(env, tableName);
    char* c_gid = jstring_to_cstring(env, globalId);
    lattice_object_t* obj = lattice_db_find_by_global_id(db, c_table, c_gid);
    free(c_table);
    free(c_gid);
    return (jlong)(intptr_t)obj;
}

JNIEXPORT jboolean JNICALL JNI_FN(nativeRemoveObject)(JNIEnv* env, jobject thiz,
                                                       jlong dbHandle, jlong objectHandle) {
    (void)env; (void)thiz;
    lattice_db_t* db = (lattice_db_t*)(intptr_t)dbHandle;
    lattice_object_t* obj = (lattice_object_t*)(intptr_t)objectHandle;
    if (db == NULL || obj == NULL) return JNI_FALSE;
    return lattice_db_remove(db, obj) == LATTICE_OK ? JNI_TRUE : JNI_FALSE;
}

// =============================================================================
// Query Operations
// =============================================================================

static jlongArray extract_results_handles_and_free(JNIEnv* env, lattice_results_t* results) {
    if (results == NULL) {
        return (*env)->NewLongArray(env, 0);
    }
    size_t count = lattice_results_count(results);
    jlongArray arr = (*env)->NewLongArray(env, (jsize)count);
    if (count > 0 && arr != NULL) {
        jlong* elements = (*env)->GetLongArrayElements(env, arr, NULL);
        for (size_t i = 0; i < count; i++) {
            elements[i] = (jlong)(intptr_t)lattice_results_get(results, i);
        }
        (*env)->ReleaseLongArrayElements(env, arr, elements, 0);
    }
    lattice_results_free(results);
    return arr;
}

JNIEXPORT jint JNICALL JNI_FN(nativeQueryCount)(JNIEnv* env, jobject thiz,
                                                 jlong dbHandle, jstring tableName,
                                                 jstring whereClause) {
    (void)thiz;
    lattice_db_t* db = (lattice_db_t*)(intptr_t)dbHandle;
    if (db == NULL) return 0;
    char* c_table = jstring_to_cstring(env, tableName);
    char* c_where = jstring_to_cstring(env, whereClause);
    size_t count = lattice_db_count(db, c_table, c_where);
    free(c_table);
    free(c_where);
    return (jint)count;
}

JNIEXPORT jlongArray JNICALL JNI_FN(nativeQueryObjects)(JNIEnv* env, jobject thiz,
                                                         jlong dbHandle, jstring tableName,
                                                         jstring whereClause, jstring orderBy,
                                                         jint limit, jlong offset) {
    (void)thiz;
    lattice_db_t* db = (lattice_db_t*)(intptr_t)dbHandle;
    if (db == NULL) return (*env)->NewLongArray(env, 0);

    char* c_table = jstring_to_cstring(env, tableName);
    char* c_where = jstring_to_cstring(env, whereClause);
    char* c_order = jstring_to_cstring(env, orderBy);
    lattice_results_t* results = lattice_db_query(db, c_table, c_where, c_order, limit, offset);
    free(c_table);
    free(c_where);
    free(c_order);
    return extract_results_handles_and_free(env, results);
}

JNIEXPORT jint JNICALL JNI_FN(nativeDeleteWhere)(JNIEnv* env, jobject thiz,
                                                  jlong dbHandle, jstring tableName,
                                                  jstring whereClause) {
    (void)thiz;
    lattice_db_t* db = (lattice_db_t*)(intptr_t)dbHandle;
    if (db == NULL) return 0;
    char* c_table = jstring_to_cstring(env, tableName);
    char* c_where = jstring_to_cstring(env, whereClause);
    size_t deleted = lattice_db_delete_where(db, c_table, c_where);
    free(c_table);
    free(c_where);
    return (jint)deleted;
}

// =============================================================================
// Transaction Operations
// =============================================================================

JNIEXPORT jboolean JNICALL JNI_FN(nativeBeginTransaction)(JNIEnv* env, jobject thiz, jlong dbHandle) {
    (void)env; (void)thiz;
    lattice_db_t* db = (lattice_db_t*)(intptr_t)dbHandle;
    if (db == NULL) return JNI_FALSE;
    return lattice_db_begin_transaction(db) == LATTICE_OK ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL JNI_FN(nativeCommitTransaction)(JNIEnv* env, jobject thiz, jlong dbHandle) {
    (void)env; (void)thiz;
    lattice_db_t* db = (lattice_db_t*)(intptr_t)dbHandle;
    if (db == NULL) return JNI_FALSE;
    return lattice_db_commit(db) == LATTICE_OK ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL JNI_FN(nativeRollbackTransaction)(JNIEnv* env, jobject thiz, jlong dbHandle) {
    (void)env; (void)thiz;
    lattice_db_t* db = (lattice_db_t*)(intptr_t)dbHandle;
    if (db != NULL) lattice_db_rollback(db);
}

// =============================================================================
// Object Property Access & Creation (v2 schema)
// =============================================================================

JNIEXPORT jlong JNICALL JNI_FN(nativeCreateObject)(JNIEnv* env, jobject thiz,
                                                    jstring tableName, jstring schemaJson) {
    (void)thiz; (void)schemaJson;
    char* c_table = jstring_to_cstring(env, tableName);
    lattice_object_t* obj = lattice_object_create(c_table);
    free(c_table);
    return (jlong)(intptr_t)obj;
}

static char* json_extract_string(const char* json, const char* key) {
    char search[128];
    snprintf(search, sizeof(search), "\"%s\"", key);
    const char* pos = strstr(json, search);
    if (!pos) return NULL;
    pos += strlen(search);
    while (*pos == ' ' || *pos == ':') pos++;
    if (*pos == 'n' && strncmp(pos, "null", 4) == 0) return NULL;
    if (*pos != '"') return NULL;
    pos++;
    const char* end = strchr(pos, '"');
    if (!end) return NULL;
    size_t len = (size_t)(end - pos);
    char* result = (char*)malloc(len + 1);
    memcpy(result, pos, len);
    result[len] = '\0';
    return result;
}

static int json_extract_int(const char* json, const char* key) {
    char search[128];
    snprintf(search, sizeof(search), "\"%s\"", key);
    const char* pos = strstr(json, search);
    if (!pos) return -1;
    pos += strlen(search);
    while (*pos == ' ' || *pos == ':') pos++;
    return atoi(pos);
}

static int json_extract_bool(const char* json, const char* key) {
    char search[128];
    snprintf(search, sizeof(search), "\"%s\"", key);
    const char* pos = strstr(json, search);
    if (!pos) return 0;
    pos += strlen(search);
    while (*pos == ' ' || *pos == ':') pos++;
    return (*pos == 't') ? 1 : 0;
}

static int json_split_objects(const char* json, char*** out_objects) {
    int capacity = 16;
    char** objects = (char**)malloc((size_t)capacity * sizeof(char*));
    int count = 0;
    int depth = 0;
    const char* start = NULL;

    for (const char* p = json; *p; p++) {
        if (*p == '{') {
            if (depth == 0) start = p;
            depth++;
        } else if (*p == '}') {
            depth--;
            if (depth == 0 && start) {
                size_t len = (size_t)(p - start + 1);
                if (count >= capacity) {
                    capacity *= 2;
                    objects = (char**)realloc(objects, (size_t)capacity * sizeof(char*));
                }
                objects[count] = (char*)malloc(len + 1);
                memcpy(objects[count], start, len);
                objects[count][len] = '\0';
                count++;
                start = NULL;
            }
        }
    }
    *out_objects = objects;
    return count;
}

JNIEXPORT jlong JNICALL JNI_FN(nativeCreateObjectWithSchema)(JNIEnv* env, jobject thiz,
                                                              jstring tableName, jstring schemaJson) {
    (void)thiz;
    char* c_table = jstring_to_cstring(env, tableName);
    char* c_schema = jstring_to_cstring(env, schemaJson);

    if (c_schema == NULL || c_schema[0] == '\0') {
        lattice_object_t* obj = lattice_object_create(c_table);
        free(c_table);
        free(c_schema);
        return (jlong)(intptr_t)obj;
    }

    char** obj_strings = NULL;
    int prop_count = json_split_objects(c_schema, &obj_strings);
    if (prop_count <= 0) {
        free(obj_strings);
        lattice_object_t* obj = lattice_object_create(c_table);
        free(c_table);
        free(c_schema);
        return (jlong)(intptr_t)obj;
    }

    lattice_property_ext_t* props =
        (lattice_property_ext_t*)calloc((size_t)prop_count, sizeof(lattice_property_ext_t));

    for (int i = 0; i < prop_count; i++) {
        lattice_property_ext_init(&props[i]);
        const char* entry = obj_strings[i];
        props[i].base.name = json_extract_string(entry, "name");
        props[i].base.type = (lattice_column_type_t)json_extract_int(entry, "type");
        props[i].base.kind = (lattice_property_kind_t)json_extract_int(entry, "kind");
        props[i].base.nullable = json_extract_bool(entry, "nullable") != 0;
        props[i].base.is_indexed = json_extract_bool(entry, "is_indexed") != 0;
        props[i].base.is_unique = json_extract_bool(entry, "is_unique") != 0;
        props[i].base.is_full_text = json_extract_bool(entry, "is_full_text") != 0;
        props[i].base.is_vector = json_extract_bool(entry, "is_vector") != 0;
        props[i].base.is_geo_bounds = json_extract_bool(entry, "is_geo_bounds") != 0;
        props[i].base.target_table = json_extract_string(entry, "target_table");
        props[i].base.link_table = json_extract_string(entry, "link_table");
        props[i].base.column_name = json_extract_string(entry, "column_name");
        props[i].no_history = json_extract_bool(entry, "is_no_history") != 0;

        char* union_spec = json_extract_string(entry, "union_spec");
        if (union_spec != NULL) {
            props[i].union_desc = parse_union_descriptor(union_spec);
            free(union_spec);
        }
    }

    lattice_object_t* obj = lattice_object_create_with_schema_v2(
        c_table, props, (size_t)prop_count, sizeof(lattice_property_ext_t)
    );

    for (int i = 0; i < prop_count; i++) {
        free((void*)props[i].base.name);
        free((void*)props[i].base.target_table);
        free((void*)props[i].base.link_table);
        free((void*)props[i].base.column_name);
        free_union_descriptor((lattice_union_descriptor_t*)props[i].union_desc);
        free(obj_strings[i]);
    }
    free(props);
    free(obj_strings);
    free(c_table);
    free(c_schema);

    return (jlong)(intptr_t)obj;
}

JNIEXPORT jlong JNICALL JNI_FN(nativeGetIntProperty)(JNIEnv* env, jobject thiz,
                                                      jlong objectHandle, jstring propertyName) {
    (void)thiz;
    lattice_object_t* obj = (lattice_object_t*)(intptr_t)objectHandle;
    if (obj == NULL) return 0;
    char* c_prop = jstring_to_cstring(env, propertyName);
    int64_t val = lattice_object_get_int(obj, c_prop);
    free(c_prop);
    return (jlong)val;
}

JNIEXPORT void JNICALL JNI_FN(nativeSetIntProperty)(JNIEnv* env, jobject thiz,
                                                     jlong objectHandle, jstring propertyName,
                                                     jlong value) {
    (void)thiz;
    lattice_object_t* obj = (lattice_object_t*)(intptr_t)objectHandle;
    if (obj == NULL) return;
    char* c_prop = jstring_to_cstring(env, propertyName);
    lattice_object_set_int(obj, c_prop, value);
    free(c_prop);
}

JNIEXPORT jdouble JNICALL JNI_FN(nativeGetDoubleProperty)(JNIEnv* env, jobject thiz,
                                                          jlong objectHandle, jstring propertyName) {
    (void)thiz;
    lattice_object_t* obj = (lattice_object_t*)(intptr_t)objectHandle;
    if (obj == NULL) return 0.0;
    char* c_prop = jstring_to_cstring(env, propertyName);
    double val = lattice_object_get_double(obj, c_prop);
    free(c_prop);
    return val;
}

JNIEXPORT void JNICALL JNI_FN(nativeSetDoubleProperty)(JNIEnv* env, jobject thiz,
                                                        jlong objectHandle, jstring propertyName,
                                                        jdouble value) {
    (void)thiz;
    lattice_object_t* obj = (lattice_object_t*)(intptr_t)objectHandle;
    if (obj == NULL) return;
    char* c_prop = jstring_to_cstring(env, propertyName);
    lattice_object_set_double(obj, c_prop, value);
    free(c_prop);
}

JNIEXPORT jstring JNICALL JNI_FN(nativeGetStringProperty)(JNIEnv* env, jobject thiz,
                                                          jlong objectHandle, jstring propertyName) {
    (void)thiz;
    lattice_object_t* obj = (lattice_object_t*)(intptr_t)objectHandle;
    if (obj == NULL) return NULL;
    char* c_prop = jstring_to_cstring(env, propertyName);
    const char* val = lattice_object_get_string(obj, c_prop);
    free(c_prop);
    return cstring_to_jstring(env, val);
}

JNIEXPORT void JNICALL JNI_FN(nativeSetStringProperty)(JNIEnv* env, jobject thiz,
                                                        jlong objectHandle, jstring propertyName,
                                                        jstring value) {
    (void)thiz;
    lattice_object_t* obj = (lattice_object_t*)(intptr_t)objectHandle;
    if (obj == NULL) return;
    char* c_prop = jstring_to_cstring(env, propertyName);
    char* c_val = jstring_to_cstring(env, value);
    if (c_val != NULL) {
        lattice_object_set_string(obj, c_prop, c_val);
        free(c_val);
    } else {
        lattice_object_set_null(obj, c_prop);
    }
    free(c_prop);
}

JNIEXPORT jboolean JNICALL JNI_FN(nativeGetBoolProperty)(JNIEnv* env, jobject thiz,
                                                         jlong objectHandle, jstring propertyName) {
    (void)thiz;
    lattice_object_t* obj = (lattice_object_t*)(intptr_t)objectHandle;
    if (obj == NULL) return JNI_FALSE;
    char* c_prop = jstring_to_cstring(env, propertyName);
    int64_t val = lattice_object_get_int(obj, c_prop);
    free(c_prop);
    return val != 0 ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL JNI_FN(nativeSetBoolProperty)(JNIEnv* env, jobject thiz,
                                                      jlong objectHandle, jstring propertyName,
                                                      jboolean value) {
    (void)thiz;
    lattice_object_t* obj = (lattice_object_t*)(intptr_t)objectHandle;
    if (obj == NULL) return;
    char* c_prop = jstring_to_cstring(env, propertyName);
    lattice_object_set_int(obj, c_prop, value ? 1 : 0);
    free(c_prop);
}

JNIEXPORT jlong JNICALL JNI_FN(nativeGetObjectId)(JNIEnv* env, jobject thiz, jlong objectHandle) {
    (void)env; (void)thiz;
    lattice_object_t* obj = (lattice_object_t*)(intptr_t)objectHandle;
    return obj ? (jlong)lattice_object_get_id(obj) : 0;
}

JNIEXPORT jstring JNICALL JNI_FN(nativeGetObjectGlobalId)(JNIEnv* env, jobject thiz, jlong objectHandle) {
    (void)thiz;
    lattice_object_t* obj = (lattice_object_t*)(intptr_t)objectHandle;
    return obj ? cstring_to_jstring(env, lattice_object_get_global_id(obj)) : NULL;
}

JNIEXPORT jstring JNICALL JNI_FN(nativeGetObjectTableName)(JNIEnv* env, jobject thiz, jlong objectHandle) {
    (void)thiz;
    lattice_object_t* obj = (lattice_object_t*)(intptr_t)objectHandle;
    return obj ? cstring_to_jstring(env, lattice_object_get_table_name(obj)) : NULL;
}

JNIEXPORT jboolean JNICALL JNI_FN(nativeHasValue)(JNIEnv* env, jobject thiz,
                                                   jlong objectHandle, jstring propertyName) {
    (void)thiz;
    lattice_object_t* obj = (lattice_object_t*)(intptr_t)objectHandle;
    if (obj == NULL) return JNI_FALSE;
    char* c_prop = jstring_to_cstring(env, propertyName);
    bool has = lattice_object_has_value(obj, c_prop);
    free(c_prop);
    return has ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL JNI_FN(nativeSetNull)(JNIEnv* env, jobject thiz,
                                              jlong objectHandle, jstring propertyName) {
    (void)thiz;
    lattice_object_t* obj = (lattice_object_t*)(intptr_t)objectHandle;
    if (obj == NULL) return;
    char* c_prop = jstring_to_cstring(env, propertyName);
    lattice_object_set_null(obj, c_prop);
    free(c_prop);
}

// =============================================================================
// Blob Property Access
// =============================================================================

JNIEXPORT jint JNICALL JNI_FN(nativeGetBlobSize)(JNIEnv* env, jobject thiz,
                                                  jlong objectHandle, jstring propertyName) {
    (void)thiz;
    lattice_object_t* obj = (lattice_object_t*)(intptr_t)objectHandle;
    if (obj == NULL) return 0;
    char* c_prop = jstring_to_cstring(env, propertyName);
    size_t size = lattice_object_get_blob(obj, c_prop, NULL, 0);
    free(c_prop);
    return (jint)size;
}

JNIEXPORT jint JNICALL JNI_FN(nativeGetBlobData)(JNIEnv* env, jobject thiz,
                                                  jlong objectHandle, jstring propertyName,
                                                  jbyteArray buffer) {
    (void)thiz;
    lattice_object_t* obj = (lattice_object_t*)(intptr_t)objectHandle;
    if (obj == NULL || buffer == NULL) return 0;
    char* c_prop = jstring_to_cstring(env, propertyName);
    jsize buf_size = (*env)->GetArrayLength(env, buffer);
    jbyte* buf = (*env)->GetByteArrayElements(env, buffer, NULL);
    size_t read = lattice_object_get_blob(obj, c_prop, (uint8_t*)buf, (size_t)buf_size);
    (*env)->ReleaseByteArrayElements(env, buffer, buf, 0);
    free(c_prop);
    return (jint)read;
}

JNIEXPORT void JNICALL JNI_FN(nativeSetBlobProperty)(JNIEnv* env, jobject thiz,
                                                      jlong objectHandle, jstring propertyName,
                                                      jbyteArray data) {
    (void)thiz;
    lattice_object_t* obj = (lattice_object_t*)(intptr_t)objectHandle;
    if (obj == NULL) return;
    char* c_prop = jstring_to_cstring(env, propertyName);
    if (data == NULL) {
        lattice_object_set_null(obj, c_prop);
    } else {
        jsize len = (*env)->GetArrayLength(env, data);
        jbyte* bytes = (*env)->GetByteArrayElements(env, data, NULL);
        lattice_object_set_blob(obj, c_prop, (const uint8_t*)bytes, (size_t)len);
        (*env)->ReleaseByteArrayElements(env, data, bytes, JNI_ABORT);
    }
    free(c_prop);
}

// =============================================================================
// Link Property Access
// =============================================================================

JNIEXPORT jlong JNICALL JNI_FN(nativeGetLinkedObject)(JNIEnv* env, jobject thiz,
                                                       jlong objectHandle, jstring propertyName) {
    (void)thiz;
    lattice_object_t* obj = (lattice_object_t*)(intptr_t)objectHandle;
    if (obj == NULL) return 0;
    char* c_prop = jstring_to_cstring(env, propertyName);
    if (!lattice_object_has_value(obj, c_prop)) {
        free(c_prop);
        return 0;
    }
    lattice_object_t* linked = lattice_object_get_object(obj, c_prop);
    free(c_prop);
    return (jlong)(intptr_t)linked;
}

JNIEXPORT void JNICALL JNI_FN(nativeSetLinkedObject)(JNIEnv* env, jobject thiz,
                                                      jlong objectHandle, jstring propertyName,
                                                      jobject linkedHandle) {
    (void)thiz;
    lattice_object_t* obj = (lattice_object_t*)(intptr_t)objectHandle;
    if (obj == NULL) return;
    char* c_prop = jstring_to_cstring(env, propertyName);
    if (linkedHandle == NULL) {
        lattice_object_set_null(obj, c_prop);
    } else {
        jclass longClass = (*env)->FindClass(env, "java/lang/Long");
        jmethodID longValue = (*env)->GetMethodID(env, longClass, "longValue", "()J");
        jlong linked = (*env)->CallLongMethod(env, linkedHandle, longValue);
        (*env)->DeleteLocalRef(env, longClass);
        if (linked != 0) {
            lattice_object_set_object(obj, c_prop, (lattice_object_t*)(intptr_t)linked);
        } else {
            lattice_object_set_null(obj, c_prop);
        }
    }
    free(c_prop);
}

JNIEXPORT jlong JNICALL JNI_FN(nativeGetLinkListHandle)(JNIEnv* env, jobject thiz,
                                                         jlong objectHandle, jstring propertyName) {
    (void)thiz;
    lattice_object_t* obj = (lattice_object_t*)(intptr_t)objectHandle;
    if (obj == NULL) return 0;
    char* c_prop = jstring_to_cstring(env, propertyName);
    lattice_link_list_t* list = lattice_object_get_link_list(obj, c_prop);
    free(c_prop);
    return (jlong)(intptr_t)list;
}

// =============================================================================
// Union Property Access (`@Union` v2 CAPI)
// =============================================================================

JNIEXPORT jlong JNICALL JNI_FN(nativeCreateUnionValue)(JNIEnv* env, jobject thiz, jstring caseName) {
    (void)thiz;
    char* c_case = jstring_to_cstring(env, caseName);
    lattice_union_value_t* uv = lattice_union_value_create(c_case ? c_case : "");
    free(c_case);
    return (jlong)(intptr_t)uv;
}

JNIEXPORT void JNICALL JNI_FN(nativeReleaseUnionValue)(JNIEnv* env, jobject thiz, jlong uvHandle) {
    (void)env; (void)thiz;
    lattice_union_value_t* uv = (lattice_union_value_t*)(intptr_t)uvHandle;
    if (uv != NULL) lattice_union_value_release(uv);
}

JNIEXPORT jstring JNICALL JNI_FN(nativeGetUnionCase)(JNIEnv* env, jobject thiz, jlong uvHandle) {
    (void)thiz;
    const lattice_union_value_t* uv = (const lattice_union_value_t*)(intptr_t)uvHandle;
    if (uv == NULL) return NULL;
    return cstring_to_jstring(env, lattice_union_value_get_case(uv));
}

JNIEXPORT jboolean JNICALL JNI_FN(nativeHasUnionField)(JNIEnv* env, jobject thiz, jlong uvHandle, jstring key) {
    (void)thiz;
    const lattice_union_value_t* uv = (const lattice_union_value_t*)(intptr_t)uvHandle;
    if (uv == NULL) return JNI_FALSE;
    char* c_key = jstring_to_cstring(env, key);
    bool has = lattice_union_value_has_field(uv, c_key);
    free(c_key);
    return has ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jlong JNICALL JNI_FN(nativeGetUnionInt)(JNIEnv* env, jobject thiz, jlong uvHandle, jstring key) {
    (void)thiz;
    const lattice_union_value_t* uv = (const lattice_union_value_t*)(intptr_t)uvHandle;
    if (uv == NULL) return 0;
    char* c_key = jstring_to_cstring(env, key);
    int64_t val = lattice_union_value_get_int(uv, c_key);
    free(c_key);
    return (jlong)val;
}

JNIEXPORT jdouble JNICALL JNI_FN(nativeGetUnionDouble)(JNIEnv* env, jobject thiz, jlong uvHandle, jstring key) {
    (void)thiz;
    const lattice_union_value_t* uv = (const lattice_union_value_t*)(intptr_t)uvHandle;
    if (uv == NULL) return 0.0;
    char* c_key = jstring_to_cstring(env, key);
    double val = lattice_union_value_get_double(uv, c_key);
    free(c_key);
    return val;
}

JNIEXPORT jstring JNICALL JNI_FN(nativeGetUnionString)(JNIEnv* env, jobject thiz, jlong uvHandle, jstring key) {
    (void)thiz;
    const lattice_union_value_t* uv = (const lattice_union_value_t*)(intptr_t)uvHandle;
    if (uv == NULL) return NULL;
    char* c_key = jstring_to_cstring(env, key);
    const char* val = lattice_union_value_get_string(uv, c_key);
    free(c_key);
    return cstring_to_jstring(env, val);
}

JNIEXPORT jbyteArray JNICALL JNI_FN(nativeGetUnionBlob)(JNIEnv* env, jobject thiz, jlong uvHandle, jstring key) {
    (void)thiz;
    const lattice_union_value_t* uv = (const lattice_union_value_t*)(intptr_t)uvHandle;
    if (uv == NULL) return (*env)->NewByteArray(env, 0);
    char* c_key = jstring_to_cstring(env, key);
    size_t sz = lattice_union_value_get_blob(uv, c_key, NULL, 0);
    jbyteArray arr = (*env)->NewByteArray(env, (jsize)sz);
    if (sz > 0 && arr != NULL) {
        jbyte* buf = (*env)->GetByteArrayElements(env, arr, NULL);
        lattice_union_value_get_blob(uv, c_key, (uint8_t*)buf, sz);
        (*env)->ReleaseByteArrayElements(env, arr, buf, 0);
    }
    free(c_key);
    return arr;
}

JNIEXPORT jlong JNICALL JNI_FN(nativeGetUnionLink)(JNIEnv* env, jobject thiz, jlong uvHandle, jstring key) {
    (void)thiz;
    const lattice_union_value_t* uv = (const lattice_union_value_t*)(intptr_t)uvHandle;
    if (uv == NULL) return 0;
    char* c_key = jstring_to_cstring(env, key);
    lattice_object_t* linked = lattice_union_value_get_link(uv, c_key);
    free(c_key);
    return (jlong)(intptr_t)linked;
}

JNIEXPORT void JNICALL JNI_FN(nativeSetUnionInt)(JNIEnv* env, jobject thiz, jlong uvHandle, jstring key, jlong value) {
    (void)thiz;
    lattice_union_value_t* uv = (lattice_union_value_t*)(intptr_t)uvHandle;
    if (uv == NULL) return;
    char* c_key = jstring_to_cstring(env, key);
    lattice_union_value_set_int(uv, c_key, value);
    free(c_key);
}

JNIEXPORT void JNICALL JNI_FN(nativeSetUnionDouble)(JNIEnv* env, jobject thiz, jlong uvHandle, jstring key, jdouble value) {
    (void)thiz;
    lattice_union_value_t* uv = (lattice_union_value_t*)(intptr_t)uvHandle;
    if (uv == NULL) return;
    char* c_key = jstring_to_cstring(env, key);
    lattice_union_value_set_double(uv, c_key, value);
    free(c_key);
}

JNIEXPORT void JNICALL JNI_FN(nativeSetUnionString)(JNIEnv* env, jobject thiz, jlong uvHandle, jstring key, jstring value) {
    (void)thiz;
    lattice_union_value_t* uv = (lattice_union_value_t*)(intptr_t)uvHandle;
    if (uv == NULL) return;
    char* c_key = jstring_to_cstring(env, key);
    char* c_val = jstring_to_cstring(env, value);
    lattice_union_value_set_string(uv, c_key, c_val ? c_val : "");
    free(c_key);
    free(c_val);
}

JNIEXPORT void JNICALL JNI_FN(nativeSetUnionBlob)(JNIEnv* env, jobject thiz, jlong uvHandle, jstring key, jbyteArray value) {
    (void)thiz;
    lattice_union_value_t* uv = (lattice_union_value_t*)(intptr_t)uvHandle;
    if (uv == NULL) return;
    char* c_key = jstring_to_cstring(env, key);
    jsize len = value ? (*env)->GetArrayLength(env, value) : 0;
    jbyte* bytes = (value && len > 0) ? (*env)->GetByteArrayElements(env, value, NULL) : NULL;
    lattice_union_value_set_blob(uv, c_key, (const uint8_t*)bytes, (size_t)len);
    if (bytes) (*env)->ReleaseByteArrayElements(env, value, bytes, JNI_ABORT);
    free(c_key);
}

JNIEXPORT void JNICALL JNI_FN(nativeSetUnionLink)(JNIEnv* env, jobject thiz, jlong uvHandle, jstring key, jlong objHandle) {
    (void)thiz;
    lattice_union_value_t* uv = (lattice_union_value_t*)(intptr_t)uvHandle;
    lattice_object_t* obj = (lattice_object_t*)(intptr_t)objHandle;
    if (uv == NULL || obj == NULL) return;
    char* c_key = jstring_to_cstring(env, key);
    lattice_union_value_set_link(uv, c_key, obj);
    free(c_key);
}

JNIEXPORT jlong JNICALL JNI_FN(nativeGetObjectUnion)(JNIEnv* env, jobject thiz, jlong objectHandle, jstring propertyName) {
    (void)thiz;
    lattice_object_t* obj = (lattice_object_t*)(intptr_t)objectHandle;
    if (obj == NULL) return 0;
    char* c_prop = jstring_to_cstring(env, propertyName);
    lattice_union_value_t* uv = lattice_object_get_union(obj, c_prop);
    free(c_prop);
    return (jlong)(intptr_t)uv;
}

JNIEXPORT jboolean JNICALL JNI_FN(nativeSetObjectUnion)(JNIEnv* env, jobject thiz, jlong objectHandle, jstring propertyName, jlong uvHandle) {
    (void)thiz;
    lattice_object_t* obj = (lattice_object_t*)(intptr_t)objectHandle;
    const lattice_union_value_t* uv = (const lattice_union_value_t*)(intptr_t)uvHandle;
    if (obj == NULL || uv == NULL) return JNI_FALSE;
    char* c_prop = jstring_to_cstring(env, propertyName);
    lattice_status_t st = lattice_object_set_union(obj, c_prop, uv);
    free(c_prop);
    return st == LATTICE_OK ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL JNI_FN(nativeClearObjectUnion)(JNIEnv* env, jobject thiz, jlong objectHandle, jstring propertyName) {
    (void)thiz;
    lattice_object_t* obj = (lattice_object_t*)(intptr_t)objectHandle;
    if (obj == NULL) return JNI_FALSE;
    char* c_prop = jstring_to_cstring(env, propertyName);
    lattice_status_t st = lattice_object_clear_union(obj, c_prop);
    free(c_prop);
    return st == LATTICE_OK ? JNI_TRUE : JNI_FALSE;
}

// =============================================================================
// Row Cache, Atomic Increment, Detached JSON & Statement Counters
// =============================================================================

JNIEXPORT jboolean JNICALL JNI_FN(nativeEnableRowCache)(JNIEnv* env, jobject thiz, jlong objectHandle) {
    (void)env; (void)thiz;
    lattice_object_t* obj = (lattice_object_t*)(intptr_t)objectHandle;
    return (obj && lattice_object_enable_row_cache(obj) == LATTICE_OK) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL JNI_FN(nativeDisableRowCache)(JNIEnv* env, jobject thiz, jlong objectHandle) {
    (void)env; (void)thiz;
    lattice_object_t* obj = (lattice_object_t*)(intptr_t)objectHandle;
    return (obj && lattice_object_disable_row_cache(obj) == LATTICE_OK) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL JNI_FN(nativeRefreshRowCache)(JNIEnv* env, jobject thiz, jlong objectHandle) {
    (void)env; (void)thiz;
    lattice_object_t* obj = (lattice_object_t*)(intptr_t)objectHandle;
    return (obj && lattice_object_refresh_row_cache(obj) == LATTICE_OK) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL JNI_FN(nativeIsRowCacheEnabled)(JNIEnv* env, jobject thiz, jlong objectHandle) {
    (void)env; (void)thiz;
    lattice_object_t* obj = (lattice_object_t*)(intptr_t)objectHandle;
    return (obj && lattice_object_is_row_cache_enabled(obj)) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL JNI_FN(nativeIncrementInt)(JNIEnv* env, jobject thiz,
                                                      jlong objectHandle, jstring propertyName, jlong delta) {
    (void)thiz;
    lattice_object_t* obj = (lattice_object_t*)(intptr_t)objectHandle;
    if (obj == NULL) return JNI_FALSE;
    char* c_prop = jstring_to_cstring(env, propertyName);
    lattice_status_t st = lattice_object_increment_int(obj, c_prop, delta);
    free(c_prop);
    return st == LATTICE_OK ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jstring JNICALL JNI_FN(nativeObjectToJson)(JNIEnv* env, jobject thiz, jlong objectHandle, jint maxDepth) {
    (void)thiz;
    lattice_object_t* obj = (lattice_object_t*)(intptr_t)objectHandle;
    if (obj == NULL) return NULL;
    char* raw = lattice_object_to_json(obj, maxDepth);
    if (raw == NULL) return NULL;
    jstring jres = cstring_to_jstring(env, raw);
    lattice_string_free(raw);
    return jres;
}

JNIEXPORT jlong JNICALL JNI_FN(nativeTotalStatementCount)(JNIEnv* env, jobject thiz) {
    (void)env; (void)thiz;
    return (jlong)lattice_db_total_statement_count();
}

JNIEXPORT jlong JNICALL JNI_FN(nativeThreadStatementCount)(JNIEnv* env, jobject thiz) {
    (void)env; (void)thiz;
    return (jlong)lattice_db_thread_statement_count();
}

// =============================================================================
// Sync, Maintenance & Sync Observers
// =============================================================================

JNIEXPORT jstring JNICALL JNI_FN(nativeReceiveSyncData)(JNIEnv* env, jobject thiz,
                                                         jlong dbHandle, jbyteArray data) {
    (void)thiz;
    lattice_db_t* db = (lattice_db_t*)(intptr_t)dbHandle;
    if (db == NULL || data == NULL) return NULL;
    jsize len = (*env)->GetArrayLength(env, data);
    jbyte* bytes = (*env)->GetByteArrayElements(env, data, NULL);
    char* result = lattice_db_receive_sync_data(db, (const uint8_t*)bytes, (size_t)len);
    (*env)->ReleaseByteArrayElements(env, data, bytes, JNI_ABORT);
    if (result == NULL) return NULL;
    jstring jresult = cstring_to_jstring(env, result);
    lattice_string_free(result);
    return jresult;
}

JNIEXPORT jstring JNICALL JNI_FN(nativeGetEventsAfter)(JNIEnv* env, jobject thiz,
                                                        jlong dbHandle, jstring globalId) {
    (void)thiz;
    lattice_db_t* db = (lattice_db_t*)(intptr_t)dbHandle;
    if (db == NULL) return NULL;
    char* c_gid = jstring_to_cstring(env, globalId);
    char* result = lattice_db_events_after(db, c_gid);
    free(c_gid);
    if (result == NULL) return NULL;
    jstring jresult = cstring_to_jstring(env, result);
    lattice_string_free(result);
    return jresult;
}

JNIEXPORT jstring JNICALL JNI_FN(nativeGetPendingEvents)(JNIEnv* env, jobject thiz, jlong dbHandle) {
    (void)thiz;
    lattice_db_t* db = (lattice_db_t*)(intptr_t)dbHandle;
    if (db == NULL) return NULL;
    char* result = lattice_db_get_pending_audit_log(db);
    if (result == NULL) return NULL;
    jstring jresult = cstring_to_jstring(env, result);
    lattice_string_free(result);
    return jresult;
}

JNIEXPORT void JNICALL JNI_FN(nativeMarkSynced)(JNIEnv* env, jobject thiz,
                                                 jlong dbHandle, jstring globalIdsJson) {
    (void)thiz;
    lattice_db_t* db = (lattice_db_t*)(intptr_t)dbHandle;
    if (db == NULL) return;
    char* c_json = jstring_to_cstring(env, globalIdsJson);
    lattice_db_mark_synced(db, c_json);
    free(c_json);
}

JNIEXPORT jlong JNICALL JNI_FN(nativeCompactAuditLog)(JNIEnv* env, jobject thiz, jlong dbHandle) {
    (void)env; (void)thiz;
    lattice_db_t* db = (lattice_db_t*)(intptr_t)dbHandle;
    return db ? (jlong)lattice_db_compact_audit_log(db) : 0;
}

JNIEXPORT jlong JNICALL JNI_FN(nativePruneAuditLog)(JNIEnv* env, jobject thiz, jlong dbHandle, jlong retentionSeconds) {
    (void)env; (void)thiz;
    lattice_db_t* db = (lattice_db_t*)(intptr_t)dbHandle;
    return db ? (jlong)lattice_db_prune_audit_log(db, retentionSeconds) : -1;
}

JNIEXPORT jboolean JNICALL JNI_FN(nativeRecordAuditWatermark)(JNIEnv* env, jobject thiz, jlong dbHandle) {
    (void)env; (void)thiz;
    lattice_db_t* db = (lattice_db_t*)(intptr_t)dbHandle;
    return (db && lattice_db_record_audit_watermark(db) == LATTICE_OK) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jlong JNICALL JNI_FN(nativeReclaimSpace)(JNIEnv* env, jobject thiz, jlong dbHandle) {
    (void)env; (void)thiz;
    lattice_db_t* db = (lattice_db_t*)(intptr_t)dbHandle;
    return db ? (jlong)lattice_db_reclaim_space(db) : -1;
}

JNIEXPORT jboolean JNICALL JNI_FN(nativeCheckpointDb)(JNIEnv* env, jobject thiz, jlong dbHandle, jboolean truncate) {
    (void)env; (void)thiz;
    lattice_db_t* db = (lattice_db_t*)(intptr_t)dbHandle;
    return (db && lattice_db_checkpoint(db, truncate ? 1 : 0) == LATTICE_OK) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL JNI_FN(nativeSetSyncFilter)(JNIEnv* env, jobject thiz, jlong dbHandle, jstring filterJson) {
    (void)thiz;
    lattice_db_t* db = (lattice_db_t*)(intptr_t)dbHandle;
    if (db == NULL) return JNI_FALSE;
    char* c_json = jstring_to_cstring(env, filterJson);
    lattice_status_t st = lattice_db_set_sync_filter(db, c_json);
    free(c_json);
    return st == LATTICE_OK ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL JNI_FN(nativeClearSyncFilter)(JNIEnv* env, jobject thiz, jlong dbHandle) {
    (void)env; (void)thiz;
    lattice_db_t* db = (lattice_db_t*)(intptr_t)dbHandle;
    return (db && lattice_db_clear_sync_filter(db) == LATTICE_OK) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL JNI_FN(nativeIsSyncConnected)(JNIEnv* env, jobject thiz, jlong dbHandle) {
    (void)env; (void)thiz;
    lattice_db_t* db = (lattice_db_t*)(intptr_t)dbHandle;
    return (db && lattice_db_is_sync_connected(db)) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jlongArray JNICALL JNI_FN(nativeGetSyncProgress)(JNIEnv* env, jobject thiz, jlong dbHandle) {
    (void)thiz;
    jlong vals[4] = { 0, 0, 0, 0 };
    lattice_db_t* db = (lattice_db_t*)(intptr_t)dbHandle;
    if (db != NULL) {
        lattice_sync_progress_t prog;
        memset(&prog, 0, sizeof(prog));
        prog.struct_size = sizeof(prog);
        if (lattice_db_get_sync_progress(db, &prog) == LATTICE_OK) {
            vals[0] = (jlong)prog.pending_upload;
            vals[1] = (jlong)prog.total_upload;
            vals[2] = (jlong)prog.acked;
            vals[3] = (jlong)prog.received;
        }
    }
    jlongArray arr = (*env)->NewLongArray(env, 4);
    if (arr) (*env)->SetLongArrayRegion(env, arr, 0, 4, vals);
    return arr;
}

typedef struct {
    jobject callback;
    jmethodID invokeMethod;
} sync_observer_ctx_t;

static void destroy_sync_observer_ctx(void* raw_ctx) {
    if (raw_ctx == NULL) return;
    sync_observer_ctx_t* ctx = (sync_observer_ctx_t*)raw_ctx;
    if (ctx->callback != NULL && g_jvm != NULL) {
        int needs_detach = 0;
        JNIEnv* env = attach_jvm_thread(&needs_detach);
        if (env != NULL) {
            (*env)->DeleteGlobalRef(env, ctx->callback);
            detach_jvm_thread(needs_detach);
        }
    }
    free(ctx);
}

static void sync_state_jni_cb(void* raw_ctx, bool connected) {
    sync_observer_ctx_t* ctx = (sync_observer_ctx_t*)raw_ctx;
    if (!ctx || !ctx->callback) return;
    int needs_detach = 0;
    JNIEnv* env = attach_jvm_thread(&needs_detach);
    if (!env) return;

    jclass boolClass = (*env)->FindClass(env, "java/lang/Boolean");
    jmethodID valueOf = (*env)->GetStaticMethodID(env, boolClass, "valueOf", "(Z)Ljava/lang/Boolean;");
    jobject boxed = (*env)->CallStaticObjectMethod(env, boolClass, valueOf, connected ? JNI_TRUE : JNI_FALSE);
    (*env)->CallObjectMethod(env, ctx->callback, ctx->invokeMethod, boxed);
    if ((*env)->ExceptionCheck(env)) (*env)->ExceptionClear(env);
    if (boxed) (*env)->DeleteLocalRef(env, boxed);
    if (boolClass) (*env)->DeleteLocalRef(env, boolClass);
    detach_jvm_thread(needs_detach);
}

static void sync_progress_jni_cb(void* raw_ctx, const lattice_sync_progress_t* prog) {
    sync_observer_ctx_t* ctx = (sync_observer_ctx_t*)raw_ctx;
    if (!ctx || !ctx->callback || !prog) return;
    int needs_detach = 0;
    JNIEnv* env = attach_jvm_thread(&needs_detach);
    if (!env) return;

    jclass longClass = (*env)->FindClass(env, "java/lang/Long");
    jmethodID valueOf = (*env)->GetStaticMethodID(env, longClass, "valueOf", "(J)Ljava/lang/Long;");
    jobject b0 = (*env)->CallStaticObjectMethod(env, longClass, valueOf, (jlong)prog->pending_upload);
    jobject b1 = (*env)->CallStaticObjectMethod(env, longClass, valueOf, (jlong)prog->total_upload);
    jobject b2 = (*env)->CallStaticObjectMethod(env, longClass, valueOf, (jlong)prog->acked);
    jobject b3 = (*env)->CallStaticObjectMethod(env, longClass, valueOf, (jlong)prog->received);
    (*env)->CallObjectMethod(env, ctx->callback, ctx->invokeMethod, b0, b1, b2, b3);
    if ((*env)->ExceptionCheck(env)) (*env)->ExceptionClear(env);
    if (b0) (*env)->DeleteLocalRef(env, b0);
    if (b1) (*env)->DeleteLocalRef(env, b1);
    if (b2) (*env)->DeleteLocalRef(env, b2);
    if (b3) (*env)->DeleteLocalRef(env, b3);
    if (longClass) (*env)->DeleteLocalRef(env, longClass);
    detach_jvm_thread(needs_detach);
}

static void sync_error_jni_cb(void* raw_ctx, const char* message) {
    sync_observer_ctx_t* ctx = (sync_observer_ctx_t*)raw_ctx;
    if (!ctx || !ctx->callback) return;
    int needs_detach = 0;
    JNIEnv* env = attach_jvm_thread(&needs_detach);
    if (!env) return;

    jstring jmsg = cstring_to_jstring(env, message ? message : "");
    (*env)->CallObjectMethod(env, ctx->callback, ctx->invokeMethod, jmsg);
    if ((*env)->ExceptionCheck(env)) (*env)->ExceptionClear(env);
    if (jmsg) (*env)->DeleteLocalRef(env, jmsg);
    detach_jvm_thread(needs_detach);
}

JNIEXPORT jlong JNICALL JNI_FN(nativeObserveSyncState)(JNIEnv* env, jobject thiz, jlong dbHandle, jobject callback) {
    (void)thiz;
    lattice_db_t* db = (lattice_db_t*)(intptr_t)dbHandle;
    if (!db || !callback) return 0;
    jclass cls = (*env)->GetObjectClass(env, callback);
    jmethodID inv = (*env)->GetMethodID(env, cls, "invoke", "(Ljava/lang/Object;)Ljava/lang/Object;");
    (*env)->DeleteLocalRef(env, cls);
    if (!inv) return 0;

    sync_observer_ctx_t* ctx = (sync_observer_ctx_t*)malloc(sizeof(sync_observer_ctx_t));
    ctx->callback = (*env)->NewGlobalRef(env, callback);
    ctx->invokeMethod = inv;
    uint64_t tok = lattice_db_observe_sync_state(db, ctx, sync_state_jni_cb, destroy_sync_observer_ctx);
    if (tok == 0) {
        (*env)->DeleteGlobalRef(env, ctx->callback);
        free(ctx);
    }
    return (jlong)tok;
}

JNIEXPORT jlong JNICALL JNI_FN(nativeObserveSyncProgress)(JNIEnv* env, jobject thiz, jlong dbHandle, jobject callback) {
    (void)thiz;
    lattice_db_t* db = (lattice_db_t*)(intptr_t)dbHandle;
    if (!db || !callback) return 0;
    jclass cls = (*env)->GetObjectClass(env, callback);
    jmethodID inv = (*env)->GetMethodID(
        env, cls, "invoke",
        "(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;"
    );
    (*env)->DeleteLocalRef(env, cls);
    if (!inv) return 0;

    sync_observer_ctx_t* ctx = (sync_observer_ctx_t*)malloc(sizeof(sync_observer_ctx_t));
    ctx->callback = (*env)->NewGlobalRef(env, callback);
    ctx->invokeMethod = inv;
    uint64_t tok = lattice_db_observe_sync_progress(db, ctx, sync_progress_jni_cb, destroy_sync_observer_ctx);
    if (tok == 0) {
        (*env)->DeleteGlobalRef(env, ctx->callback);
        free(ctx);
    }
    return (jlong)tok;
}

JNIEXPORT jlong JNICALL JNI_FN(nativeObserveSyncError)(JNIEnv* env, jobject thiz, jlong dbHandle, jobject callback) {
    (void)thiz;
    lattice_db_t* db = (lattice_db_t*)(intptr_t)dbHandle;
    if (!db || !callback) return 0;
    jclass cls = (*env)->GetObjectClass(env, callback);
    jmethodID inv = (*env)->GetMethodID(env, cls, "invoke", "(Ljava/lang/Object;)Ljava/lang/Object;");
    (*env)->DeleteLocalRef(env, cls);
    if (!inv) return 0;

    sync_observer_ctx_t* ctx = (sync_observer_ctx_t*)malloc(sizeof(sync_observer_ctx_t));
    ctx->callback = (*env)->NewGlobalRef(env, callback);
    ctx->invokeMethod = inv;
    uint64_t tok = lattice_db_observe_sync_error(db, ctx, sync_error_jni_cb, destroy_sync_observer_ctx);
    if (tok == 0) {
        (*env)->DeleteGlobalRef(env, ctx->callback);
        free(ctx);
    }
    return (jlong)tok;
}

JNIEXPORT void JNICALL JNI_FN(nativeRemoveSyncObserver)(JNIEnv* env, jobject thiz, jlong dbHandle, jlong token) {
    (void)env; (void)thiz;
    lattice_db_t* db = (lattice_db_t*)(intptr_t)dbHandle;
    if (db != NULL && token != 0) {
        lattice_db_remove_sync_observer(db, (uint64_t)token);
    }
}

// =============================================================================
// Results & Link List Operations
// =============================================================================

JNIEXPORT jint JNICALL JNI_FN(nativeResultsCount)(JNIEnv* env, jobject thiz, jlong resultsHandle) {
    (void)env; (void)thiz;
    lattice_results_t* results = (lattice_results_t*)(intptr_t)resultsHandle;
    return results ? (jint)lattice_results_count(results) : 0;
}

JNIEXPORT jlong JNICALL JNI_FN(nativeResultsGet)(JNIEnv* env, jobject thiz, jlong resultsHandle, jint index) {
    (void)env; (void)thiz;
    lattice_results_t* results = (lattice_results_t*)(intptr_t)resultsHandle;
    if (!results) return 0;
    return (jlong)(intptr_t)lattice_results_get(results, (size_t)index);
}

JNIEXPORT void JNICALL JNI_FN(nativeResultsFree)(JNIEnv* env, jobject thiz, jlong resultsHandle) {
    (void)env; (void)thiz;
    lattice_results_t* results = (lattice_results_t*)(intptr_t)resultsHandle;
    if (results) lattice_results_free(results);
}

JNIEXPORT jint JNICALL JNI_FN(nativeLinkListCount)(JNIEnv* env, jobject thiz, jlong listHandle) {
    (void)env; (void)thiz;
    lattice_link_list_t* list = (lattice_link_list_t*)(intptr_t)listHandle;
    return list ? (jint)lattice_link_list_size(list) : 0;
}

JNIEXPORT jlong JNICALL JNI_FN(nativeLinkListGet)(JNIEnv* env, jobject thiz, jlong listHandle, jint index) {
    (void)env; (void)thiz;
    lattice_link_list_t* list = (lattice_link_list_t*)(intptr_t)listHandle;
    return list ? (jlong)(intptr_t)lattice_link_list_get(list, (size_t)index) : 0;
}

JNIEXPORT void JNICALL JNI_FN(nativeLinkListAppend)(JNIEnv* env, jobject thiz, jlong listHandle, jlong objectHandle) {
    (void)env; (void)thiz;
    lattice_link_list_t* list = (lattice_link_list_t*)(intptr_t)listHandle;
    lattice_object_t* obj = (lattice_object_t*)(intptr_t)objectHandle;
    if (list && obj) lattice_link_list_push_back(list, obj);
}

JNIEXPORT void JNICALL JNI_FN(nativeLinkListInsert)(JNIEnv* env, jobject thiz,
                                                     jlong listHandle, jint index, jlong objectHandle) {
    (void)env; (void)thiz; (void)index;
    lattice_link_list_t* list = (lattice_link_list_t*)(intptr_t)listHandle;
    lattice_object_t* obj = (lattice_object_t*)(intptr_t)objectHandle;
    if (list && obj) lattice_link_list_push_back(list, obj);
}

JNIEXPORT void JNICALL JNI_FN(nativeLinkListRemove)(JNIEnv* env, jobject thiz, jlong listHandle, jint index) {
    (void)env; (void)thiz;
    lattice_link_list_t* list = (lattice_link_list_t*)(intptr_t)listHandle;
    if (list) lattice_link_list_erase(list, (size_t)index);
}

JNIEXPORT void JNICALL JNI_FN(nativeLinkListClear)(JNIEnv* env, jobject thiz, jlong listHandle) {
    (void)env; (void)thiz;
    lattice_link_list_t* list = (lattice_link_list_t*)(intptr_t)listHandle;
    if (list) lattice_link_list_clear(list);
}

JNIEXPORT void JNICALL JNI_FN(nativeLinkListRelease)(JNIEnv* env, jobject thiz, jlong listHandle) {
    (void)env; (void)thiz;
    lattice_link_list_t* list = (lattice_link_list_t*)(intptr_t)listHandle;
    if (list) lattice_link_list_release(list);
}

JNIEXPORT void JNICALL JNI_FN(nativeFreeString)(JNIEnv* env, jobject thiz, jlong ptr) {
    (void)env; (void)thiz;
    char* str = (char*)(intptr_t)ptr;
    if (str) lattice_string_free(str);
}

// =============================================================================
// Query Extensions (Distinct, FTS5, KNN Vector, R*Tree GeoBounds) & Attach/Detach
// =============================================================================

JNIEXPORT jlong JNICALL JNI_FN(nativeQueryDistinct)(JNIEnv* env, jobject thiz,
                                                     jlong dbHandle, jstring tableName,
                                                     jstring distinctBy, jstring whereClause,
                                                     jstring orderBy, jlong limit, jlong offset) {
    (void)thiz;
    lattice_db_t* db = (lattice_db_t*)(intptr_t)dbHandle;
    if (!db) return 0;
    char* c_table = jstring_to_cstring(env, tableName);
    char* c_distinct = jstring_to_cstring(env, distinctBy);
    char* c_where = jstring_to_cstring(env, whereClause);
    char* c_order = jstring_to_cstring(env, orderBy);
    lattice_results_t* results = lattice_db_query_distinct(db, c_table, c_distinct, c_where, c_order, limit, offset);
    free(c_table);
    free(c_distinct);
    free(c_where);
    free(c_order);
    return (jlong)(intptr_t)results;
}

JNIEXPORT jint JNICALL JNI_FN(nativeCountDistinct)(JNIEnv* env, jobject thiz,
                                                    jlong dbHandle, jstring tableName,
                                                    jstring whereClause, jstring groupBy,
                                                    jstring distinctBy) {
    (void)thiz;
    lattice_db_t* db = (lattice_db_t*)(intptr_t)dbHandle;
    if (!db) return 0;
    char* c_table = jstring_to_cstring(env, tableName);
    char* c_where = jstring_to_cstring(env, whereClause);
    char* c_group = jstring_to_cstring(env, groupBy);
    char* c_distinct = jstring_to_cstring(env, distinctBy);
    size_t count = lattice_db_count_distinct(db, c_table, c_where, c_group, c_distinct);
    free(c_table);
    free(c_where);
    free(c_group);
    free(c_distinct);
    return (jint)count;
}

JNIEXPORT jlong JNICALL JNI_FN(nativeQueryFts)(JNIEnv* env, jobject thiz,
                                                jlong dbHandle, jstring tableName,
                                                jstring columnName, jstring matchExpression,
                                                jstring orderBy, jlong limit) {
    (void)thiz;
    lattice_db_t* db = (lattice_db_t*)(intptr_t)dbHandle;
    if (!db) return 0;
    char* c_table = jstring_to_cstring(env, tableName);
    char* c_col = jstring_to_cstring(env, columnName);
    char* c_match = jstring_to_cstring(env, matchExpression);
    char* c_order = jstring_to_cstring(env, orderBy);
    lattice_results_t* results = lattice_db_query_fts(db, c_table, c_col, c_match, c_order, limit);
    free(c_table);
    free(c_col);
    free(c_match);
    free(c_order);
    return (jlong)(intptr_t)results;
}

JNIEXPORT jobjectArray JNICALL JNI_FN(nativeQueryNearest)(
    JNIEnv* env,
    jobject thiz,
    jlong dbHandle,
    jstring tableName,
    jstring columnName,
    jbyteArray queryVectorBytes,
    jint k,
    jint metricOrdinal,
    jstring whereClause,
    jdoubleArray outDistances
) {
    (void)thiz;
    jclass strClass = (*env)->FindClass(env, "java/lang/String");
    lattice_db_t* db = (lattice_db_t*)(intptr_t)dbHandle;
    if (!db || !queryVectorBytes || k <= 0) {
        return (*env)->NewObjectArray(env, 0, strClass, NULL);
    }

    char* c_table = jstring_to_cstring(env, tableName);
    char* c_col = jstring_to_cstring(env, columnName);
    char* c_where = jstring_to_cstring(env, whereClause);
    jsize vec_len = (*env)->GetArrayLength(env, queryVectorBytes);
    jbyte* vec_bytes = (*env)->GetByteArrayElements(env, queryVectorBytes, NULL);

    lattice_knn_result_t* res = lattice_db_query_nearest(
        db, c_table, c_col, (const uint8_t*)vec_bytes, (size_t)vec_len,
        k, (lattice_distance_metric_t)metricOrdinal, c_where
    );

    if (vec_bytes) (*env)->ReleaseByteArrayElements(env, queryVectorBytes, vec_bytes, JNI_ABORT);
    free(c_table);
    free(c_col);
    free(c_where);

    if (res == NULL) {
        return (*env)->NewObjectArray(env, 0, strClass, NULL);
    }

    size_t count = lattice_knn_results_count(res);
    jsize out_cap = outDistances ? (*env)->GetArrayLength(env, outDistances) : 0;
    jobjectArray gids = (*env)->NewObjectArray(env, (jsize)count, strClass, NULL);

    for (size_t i = 0; i < count; i++) {
        jstring jgid = cstring_to_jstring(env, res[i].global_id ? res[i].global_id : "");
        (*env)->SetObjectArrayElement(env, gids, (jsize)i, jgid);
        if (jgid) (*env)->DeleteLocalRef(env, jgid);
        if ((jsize)i < out_cap) {
            jdouble d = res[i].distance;
            (*env)->SetDoubleArrayRegion(env, outDistances, (jsize)i, 1, &d);
        }
    }

    lattice_knn_results_free(res, count);
    return gids;
}

JNIEXPORT jlongArray JNICALL JNI_FN(nativeQueryWithinBounds)(
    JNIEnv* env,
    jobject thiz,
    jlong dbHandle,
    jstring tableName,
    jstring columnName,
    jdouble minLat,
    jdouble maxLat,
    jdouble minLon,
    jdouble maxLon,
    jstring whereClause,
    jlong limit
) {
    (void)thiz;
    lattice_db_t* db = (lattice_db_t*)(intptr_t)dbHandle;
    if (!db) return (*env)->NewLongArray(env, 0);

    char* c_table = jstring_to_cstring(env, tableName);
    char* c_col = jstring_to_cstring(env, columnName);
    char* c_where = jstring_to_cstring(env, whereClause);

    lattice_results_t* results = lattice_db_query_within_bounds(
        db, c_table, c_col, minLat, maxLat, minLon, maxLon, c_where, limit
    );

    free(c_table);
    free(c_col);
    free(c_where);
    return extract_results_handles_and_free(env, results);
}

JNIEXPORT jboolean JNICALL JNI_FN(nativeAttachDb)(JNIEnv* env, jobject thiz, jlong dbHandle, jlong otherHandle) {
    (void)env; (void)thiz;
    lattice_db_t* db = (lattice_db_t*)(intptr_t)dbHandle;
    lattice_db_t* other = (lattice_db_t*)(intptr_t)otherHandle;
    if (!db || !other) return JNI_FALSE;
    return lattice_db_attach(db, other) == LATTICE_OK ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL JNI_FN(nativeDetachDb)(JNIEnv* env, jobject thiz, jlong dbHandle, jlong otherHandle) {
    (void)env; (void)thiz;
    lattice_db_t* db = (lattice_db_t*)(intptr_t)dbHandle;
    lattice_db_t* other = (lattice_db_t*)(intptr_t)otherHandle;
    if (!db || !other) return JNI_FALSE;
    return lattice_db_detach(db, other) == LATTICE_OK ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jstring JNICALL JNI_FN(nativeLastAttachError)(JNIEnv* env, jobject thiz, jlong dbHandle) {
    (void)thiz;
    lattice_db_t* db = (lattice_db_t*)(intptr_t)dbHandle;
    if (!db) return NULL;
    return cstring_to_jstring(env, lattice_db_last_attach_error(db));
}

// =============================================================================
// Table Observer Operations
// =============================================================================

static void table_observer_callback(void* context, const char* operation,
                                     int64_t row_id, const char* global_id) {
    if (context == NULL || (uintptr_t)context < 0x1000 || g_jvm == NULL) return;
    observer_context_t* ctx = (observer_context_t*)context;
    if (ctx->callback == NULL || ctx->invokeMethod == NULL) return;

    int needs_detach = 0;
    JNIEnv* env = attach_jvm_thread(&needs_detach);
    if (env == NULL) return;

    if ((*env)->GetObjectRefType(env, ctx->callback) != JNIGlobalRefType) {
        detach_jvm_thread(needs_detach);
        return;
    }

    jstring j_operation = cstring_to_jstring(env, operation);
    jstring j_global_id = cstring_to_jstring(env, global_id);
    jclass longClass = (*env)->FindClass(env, "java/lang/Long");
    jmethodID longValueOf = (*env)->GetStaticMethodID(env, longClass, "valueOf", "(J)Ljava/lang/Long;");
    jobject j_row_id = (*env)->CallStaticObjectMethod(env, longClass, longValueOf, (jlong)row_id);

    (*env)->CallObjectMethod(env, ctx->callback, ctx->invokeMethod, j_operation, j_row_id, j_global_id);
    if ((*env)->ExceptionCheck(env)) (*env)->ExceptionClear(env);

    if (j_operation) (*env)->DeleteLocalRef(env, j_operation);
    if (j_row_id) (*env)->DeleteLocalRef(env, j_row_id);
    if (j_global_id) (*env)->DeleteLocalRef(env, j_global_id);
    if (longClass) (*env)->DeleteLocalRef(env, longClass);
    detach_jvm_thread(needs_detach);
}

JNIEXPORT jlong JNICALL JNI_FN(nativeObserveTable)(JNIEnv* env, jobject thiz,
                                                    jlong dbHandle, jstring tableName,
                                                    jobject callback) {
    (void)thiz;
    lattice_db_t* db = (lattice_db_t*)(intptr_t)dbHandle;
    if (db == NULL || callback == NULL) return 0;

    char* c_table = jstring_to_cstring(env, tableName);
    observer_context_t* ctx = (observer_context_t*)malloc(sizeof(observer_context_t));
    if (ctx == NULL) {
        free(c_table);
        return 0;
    }

    ctx->callback = (*env)->NewGlobalRef(env, callback);
    if (ctx->callback == NULL) {
        free(ctx);
        free(c_table);
        return 0;
    }

    jclass callbackClass = (*env)->GetObjectClass(env, callback);
    ctx->invokeMethod = (*env)->GetMethodID(
        env, callbackClass, "invoke",
        "(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;"
    );
    (*env)->DeleteLocalRef(env, callbackClass);

    if (ctx->invokeMethod == NULL) {
        (*env)->DeleteGlobalRef(env, ctx->callback);
        free(ctx);
        free(c_table);
        return 0;
    }

    uint64_t token = lattice_db_observe_table(db, c_table, ctx, table_observer_callback);
    free(c_table);
    if (token == 0) {
        (*env)->DeleteGlobalRef(env, ctx->callback);
        free(ctx);
        return 0;
    }
    ctx->token = token;
    return (jlong)(intptr_t)ctx;
}

JNIEXPORT void JNICALL JNI_FN(nativeRemoveTableObserver)(JNIEnv* env, jobject thiz,
                                                          jlong dbHandle, jstring tableName,
                                                          jlong contextHandle) {
    (void)thiz;
    lattice_db_t* db = (lattice_db_t*)(intptr_t)dbHandle;
    observer_context_t* ctx = (observer_context_t*)(intptr_t)contextHandle;
    if (ctx == NULL) return;

    if (db != NULL && ctx->token != 0) {
        char* c_table = jstring_to_cstring(env, tableName);
        lattice_db_remove_table_observer(db, c_table, ctx->token);
        free(c_table);
    }
    if (ctx->callback != NULL) {
        (*env)->DeleteGlobalRef(env, ctx->callback);
    }
    free(ctx);
}

// =============================================================================
// Dynamic Network Factory & WebSocket Bridge (Zero-Leak + TLS Cert Pinning)
// =============================================================================

typedef struct {
    jobject websocket;      // GlobalRef to JniWebSocketWrapper
    jmethodID connectMethod;
    jmethodID disconnectMethod;
    jmethodID stateMethod;
    jmethodID sendMethod;
    jmethodID configurePinMethod;
    jmethodID clearHandleMethod;
    lattice_websocket_client_t* client;
} ws_bridge_context_t;

typedef struct {
    lattice_websocket_client_t* client;
    ws_bridge_context_t* ctx;
} ws_client_entry_t;

static ws_client_entry_t* g_ws_clients = NULL;
static size_t g_ws_client_capacity = 0;
static pthread_mutex_t g_ws_client_map_mutex = PTHREAD_MUTEX_INITIALIZER;

static void register_ws_client(lattice_websocket_client_t* client, ws_bridge_context_t* ctx) {
    pthread_mutex_lock(&g_ws_client_map_mutex);
    for (size_t i = 0; i < g_ws_client_capacity; i++) {
        if (g_ws_clients[i].client == NULL) {
            g_ws_clients[i].client = client;
            g_ws_clients[i].ctx = ctx;
            pthread_mutex_unlock(&g_ws_client_map_mutex);
            return;
        }
    }
    size_t new_cap = (g_ws_client_capacity == 0) ? 32 : (g_ws_client_capacity * 2);
    ws_client_entry_t* grown = (ws_client_entry_t*)realloc(g_ws_clients, new_cap * sizeof(ws_client_entry_t));
    if (grown != NULL) {
        memset(grown + g_ws_client_capacity, 0, (new_cap - g_ws_client_capacity) * sizeof(ws_client_entry_t));
        grown[g_ws_client_capacity].client = client;
        grown[g_ws_client_capacity].ctx = ctx;
        g_ws_clients = grown;
        g_ws_client_capacity = new_cap;
    } else {
        LATTICE_LOGE("Out of memory growing WebSocket client registry");
    }
    pthread_mutex_unlock(&g_ws_client_map_mutex);
}

static void unregister_ws_context(ws_bridge_context_t* ctx) {
    pthread_mutex_lock(&g_ws_client_map_mutex);
    for (size_t i = 0; i < g_ws_client_capacity; i++) {
        if (g_ws_clients[i].ctx == ctx) {
            g_ws_clients[i].client = NULL;
            g_ws_clients[i].ctx = NULL;
        }
    }
    pthread_mutex_unlock(&g_ws_client_map_mutex);
}

static ws_bridge_context_t* find_ws_context(void* user_data) {
    if (user_data == NULL || (uintptr_t)user_data < 0x1000) return NULL;
    pthread_mutex_lock(&g_ws_client_map_mutex);
    ws_bridge_context_t* found = NULL;
    for (size_t i = 0; i < g_ws_client_capacity; i++) {
        if (g_ws_clients[i].ctx == (ws_bridge_context_t*)user_data ||
            g_ws_clients[i].client == (lattice_websocket_client_t*)user_data) {
            found = g_ws_clients[i].ctx;
            break;
        }
    }
    pthread_mutex_unlock(&g_ws_client_map_mutex);
    return found ? found : (ws_bridge_context_t*)user_data;
}

typedef struct {
    jobject factory;
    jmethodID createMethod;
} factory_bridge_context_t;

static void ws_connect_callback(void* user_data, const char* url, const char* headers_json) {
    ws_bridge_context_t* ctx = find_ws_context(user_data);
    if (!ctx || !ctx->websocket) return;
    int needs_detach = 0;
    JNIEnv* env = attach_jvm_thread(&needs_detach);
    if (!env) return;

    jstring j_url = cstring_to_jstring(env, url);
    jstring j_headers = cstring_to_jstring(env, headers_json ? headers_json : "{}");
    (*env)->CallVoidMethod(env, ctx->websocket, ctx->connectMethod, j_url, j_headers);
    if (j_url) (*env)->DeleteLocalRef(env, j_url);
    if (j_headers) (*env)->DeleteLocalRef(env, j_headers);
    if ((*env)->ExceptionCheck(env)) (*env)->ExceptionClear(env);
    detach_jvm_thread(needs_detach);
}

static void ws_disconnect_callback(void* user_data) {
    ws_bridge_context_t* ctx = find_ws_context(user_data);
    if (!ctx || !ctx->websocket) return;
    int needs_detach = 0;
    JNIEnv* env = attach_jvm_thread(&needs_detach);
    if (!env) return;

    (*env)->CallVoidMethod(env, ctx->websocket, ctx->disconnectMethod);
    if ((*env)->ExceptionCheck(env)) (*env)->ExceptionClear(env);
    detach_jvm_thread(needs_detach);
}

static lattice_websocket_state_t ws_state_callback(void* user_data) {
    ws_bridge_context_t* ctx = find_ws_context(user_data);
    if (!ctx || !ctx->websocket) return LATTICE_WS_CLOSED;
    int needs_detach = 0;
    JNIEnv* env = attach_jvm_thread(&needs_detach);
    if (!env) return LATTICE_WS_CLOSED;

    jint state = (*env)->CallIntMethod(env, ctx->websocket, ctx->stateMethod);
    if ((*env)->ExceptionCheck(env)) {
        (*env)->ExceptionClear(env);
        state = 3;
    }
    detach_jvm_thread(needs_detach);
    switch (state) {
        case 0: return LATTICE_WS_CONNECTING;
        case 1: return LATTICE_WS_OPEN;
        case 2: return LATTICE_WS_CLOSING;
        default: return LATTICE_WS_CLOSED;
    }
}

static void ws_send_callback(void* user_data, lattice_websocket_msg_type_t type,
                             const uint8_t* data, size_t data_size) {
    ws_bridge_context_t* ctx = find_ws_context(user_data);
    if (!ctx || !ctx->websocket) return;
    int needs_detach = 0;
    JNIEnv* env = attach_jvm_thread(&needs_detach);
    if (!env) return;

    jint j_type = (type == LATTICE_WS_MSG_TEXT) ? 0 : 1;
    jbyteArray j_data = (*env)->NewByteArray(env, (jsize)data_size);
    if (j_data && data_size > 0) {
        (*env)->SetByteArrayRegion(env, j_data, 0, (jsize)data_size, (const jbyte*)data);
    }
    (*env)->CallVoidMethod(env, ctx->websocket, ctx->sendMethod, j_type, j_data);
    if (j_data) (*env)->DeleteLocalRef(env, j_data);
    if ((*env)->ExceptionCheck(env)) (*env)->ExceptionClear(env);
    detach_jvm_thread(needs_detach);
}

static bool ws_configure_pin_callback(void* user_data, const char* sha256_hex) {
    ws_bridge_context_t* ctx = find_ws_context(user_data);
    if (!ctx || !ctx->websocket || !ctx->configurePinMethod) return false;
    int needs_detach = 0;
    JNIEnv* env = attach_jvm_thread(&needs_detach);
    if (!env) return false;

    jstring j_pin = cstring_to_jstring(env, sha256_hex ? sha256_hex : "");
    jboolean ok = (*env)->CallBooleanMethod(env, ctx->websocket, ctx->configurePinMethod, j_pin);
    if (j_pin) (*env)->DeleteLocalRef(env, j_pin);
    if ((*env)->ExceptionCheck(env)) {
        (*env)->ExceptionClear(env);
        ok = JNI_FALSE;
    }
    detach_jvm_thread(needs_detach);
    return ok == JNI_TRUE;
}

static void ws_destroy_callback(void* user_data) {
    if (user_data == NULL) return;
    ws_bridge_context_t* ctx = (ws_bridge_context_t*)user_data;
    unregister_ws_context(ctx);

    if (ctx->websocket != NULL && g_jvm != NULL) {
        int needs_detach = 0;
        JNIEnv* env = attach_jvm_thread(&needs_detach);
        if (env != NULL) {
            if (ctx->clearHandleMethod != NULL) {
                (*env)->CallVoidMethod(env, ctx->websocket, ctx->clearHandleMethod);
                if ((*env)->ExceptionCheck(env)) (*env)->ExceptionClear(env);
            }
            (*env)->DeleteGlobalRef(env, ctx->websocket);
            detach_jvm_thread(needs_detach);
        }
    }
    free(ctx);
}

static lattice_websocket_client_t* factory_create_ws_callback(void* user_data) {
    if (user_data == NULL || g_jvm == NULL) return NULL;
    factory_bridge_context_t* factory_ctx = (factory_bridge_context_t*)user_data;

    int needs_detach = 0;
    JNIEnv* env = attach_jvm_thread(&needs_detach);
    if (!env) return NULL;

    jobject ws = (*env)->CallObjectMethod(env, factory_ctx->factory, factory_ctx->createMethod);
    if (ws == NULL || (*env)->ExceptionCheck(env)) {
        (*env)->ExceptionClear(env);
        detach_jvm_thread(needs_detach);
        return NULL;
    }

    ws_bridge_context_t* ws_ctx = (ws_bridge_context_t*)calloc(1, sizeof(ws_bridge_context_t));
    if (ws_ctx == NULL) {
        (*env)->DeleteLocalRef(env, ws);
        detach_jvm_thread(needs_detach);
        return NULL;
    }

    ws_ctx->websocket = (*env)->NewGlobalRef(env, ws);
    (*env)->DeleteLocalRef(env, ws);
    if (ws_ctx->websocket == NULL) {
        free(ws_ctx);
        detach_jvm_thread(needs_detach);
        return NULL;
    }

    jclass wsClass = (*env)->GetObjectClass(env, ws_ctx->websocket);
    ws_ctx->connectMethod = (*env)->GetMethodID(env, wsClass, "connectWithHeaders", "(Ljava/lang/String;Ljava/lang/String;)V");
    ws_ctx->disconnectMethod = (*env)->GetMethodID(env, wsClass, "disconnect", "()V");
    ws_ctx->stateMethod = (*env)->GetMethodID(env, wsClass, "stateOrdinal", "()I");
    ws_ctx->sendMethod = (*env)->GetMethodID(env, wsClass, "sendBytes", "(I[B)V");
    ws_ctx->configurePinMethod = (*env)->GetMethodID(env, wsClass, "configurePin", "(Ljava/lang/String;)Z");
    if ((*env)->ExceptionCheck(env)) (*env)->ExceptionClear(env);
    ws_ctx->clearHandleMethod = (*env)->GetMethodID(env, wsClass, "clearClientHandle", "()V");
    if ((*env)->ExceptionCheck(env)) (*env)->ExceptionClear(env);

    ws_ctx->client = lattice_websocket_client_create(
        ws_ctx,
        ws_connect_callback,
        ws_disconnect_callback,
        ws_state_callback,
        ws_send_callback
    );
    lattice_websocket_client_set_configure_pin(ws_ctx->client, ws_ctx, ws_configure_pin_callback);
    lattice_websocket_client_set_on_destroy(ws_ctx->client, ws_ctx, ws_destroy_callback);

    register_ws_client(ws_ctx->client, ws_ctx);

    jmethodID setHandleMethod = (*env)->GetMethodID(env, wsClass, "setClientHandle", "(J)V");
    if (setHandleMethod != NULL) {
        (*env)->CallVoidMethod(env, ws_ctx->websocket, setHandleMethod, (jlong)(intptr_t)ws_ctx->client);
    }
    (*env)->DeleteLocalRef(env, wsClass);

    detach_jvm_thread(needs_detach);
    return ws_ctx->client;
}

static void factory_destroy_callback(void* user_data) {
    if (user_data == NULL) return;
    factory_bridge_context_t* ctx = (factory_bridge_context_t*)user_data;
    if (g_jvm != NULL && ctx->factory != NULL) {
        int needs_detach = 0;
        JNIEnv* env = attach_jvm_thread(&needs_detach);
        if (env != NULL) {
            (*env)->DeleteGlobalRef(env, ctx->factory);
            detach_jvm_thread(needs_detach);
        }
    }
    free(ctx);
}

JNIEXPORT jlong JNICALL JNI_FN(nativeCreateNetworkFactory)(JNIEnv* env, jobject thiz, jobject factory) {
    (void)thiz;
    if (factory == NULL) return 0;
    factory_bridge_context_t* ctx = (factory_bridge_context_t*)malloc(sizeof(factory_bridge_context_t));
    if (ctx == NULL) return 0;

    ctx->factory = (*env)->NewGlobalRef(env, factory);
    jclass factoryClass = (*env)->GetObjectClass(env, factory);
    ctx->createMethod = (*env)->GetMethodID(env, factoryClass, "createWebSocket", "()Lcom/lattice/LatticeWebSocket;");
    (*env)->DeleteLocalRef(env, factoryClass);

    if (ctx->createMethod == NULL) {
        (*env)->DeleteGlobalRef(env, ctx->factory);
        free(ctx);
        return 0;
    }

    lattice_network_factory_t* c_factory = lattice_network_factory_create(
        ctx, factory_create_ws_callback, factory_destroy_callback
    );
    return (jlong)(intptr_t)c_factory;
}

JNIEXPORT void JNICALL JNI_FN(nativeSetGlobalNetworkFactory)(JNIEnv* env, jobject thiz, jlong factoryHandle) {
    (void)env; (void)thiz;
    lattice_network_factory_t* factory = (lattice_network_factory_t*)(intptr_t)factoryHandle;
    if (factory != NULL) lattice_set_network_factory(factory);
}

JNIEXPORT void JNICALL JNI_FN(nativeTriggerOnOpen)(JNIEnv* env, jobject thiz, jlong clientHandle) {
    (void)env; (void)thiz;
    lattice_websocket_client_t* client = (lattice_websocket_client_t*)(intptr_t)clientHandle;
    if (client != NULL) lattice_websocket_client_trigger_on_open(client);
}

JNIEXPORT void JNICALL JNI_FN(nativeTriggerOnMessage)(JNIEnv* env, jobject thiz,
                                                       jlong clientHandle, jint type, jbyteArray data) {
    (void)thiz;
    lattice_websocket_client_t* client = (lattice_websocket_client_t*)(intptr_t)clientHandle;
    if (client == NULL) return;
    lattice_websocket_msg_type_t msg_type = (type == 0) ? LATTICE_WS_MSG_TEXT : LATTICE_WS_MSG_BINARY;
    jsize len = data ? (*env)->GetArrayLength(env, data) : 0;
    jbyte* bytes = (data && len > 0) ? (*env)->GetByteArrayElements(env, data, NULL) : NULL;
    lattice_websocket_client_trigger_on_message(client, msg_type, (const uint8_t*)bytes, (size_t)len);
    if (bytes) (*env)->ReleaseByteArrayElements(env, data, bytes, JNI_ABORT);
}

JNIEXPORT void JNICALL JNI_FN(nativeTriggerOnError)(JNIEnv* env, jobject thiz, jlong clientHandle, jstring error) {
    (void)thiz;
    lattice_websocket_client_t* client = (lattice_websocket_client_t*)(intptr_t)clientHandle;
    if (client == NULL) return;
    char* c_error = jstring_to_cstring(env, error);
    lattice_websocket_client_trigger_on_error(client, c_error ? c_error : "Unknown error");
    free(c_error);
}

JNIEXPORT void JNICALL JNI_FN(nativeTriggerOnClose)(JNIEnv* env, jobject thiz,
                                                     jlong clientHandle, jint code, jstring reason) {
    (void)thiz;
    lattice_websocket_client_t* client = (lattice_websocket_client_t*)(intptr_t)clientHandle;
    if (client == NULL) return;
    char* c_reason = jstring_to_cstring(env, reason);
    lattice_websocket_client_trigger_on_close(client, code, c_reason ? c_reason : "");
    free(c_reason);
}
