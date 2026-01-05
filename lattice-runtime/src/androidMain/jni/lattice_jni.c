/**
 * JNI bridge between Kotlin/Android and the Lattice C API.
 *
 * This file implements all the native methods declared in NativeBridge.kt
 * for the Android platform.
 */

#include <jni.h>
#include <string.h>
#include <stdlib.h>
#include <pthread.h>
#include <android/log.h>
#include "lattice.h"

// Helper macros for JNI function names
#define JNI_FN(name) Java_com_lattice_NativeBridge_##name

// Global JVM reference for callbacks
static JavaVM* g_jvm = NULL;

// Called when the library is loaded
JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM* vm, void* reserved) {
    g_jvm = vm;
    return JNI_VERSION_1_6;
}

// Observer callback context - stores everything needed to call back into Java
typedef struct {
    jobject callback;       // Global reference to Kotlin callback object
    jmethodID invokeMethod; // Method ID for the invoke() method
    uint64_t token;         // Observer token from C API
} observer_context_t;

// Helper to convert jstring to C string (caller must free)
static char* jstring_to_cstring(JNIEnv* env, jstring str) {
    if (str == NULL) return NULL;
    const char* utf = (*env)->GetStringUTFChars(env, str, NULL);
    char* result = strdup(utf);
    (*env)->ReleaseStringUTFChars(env, str, utf);
    return result;
}

// Helper to create jstring from C string (handles NULL)
static jstring cstring_to_jstring(JNIEnv* env, const char* str) {
    if (str == NULL) return NULL;
    return (*env)->NewStringUTF(env, str);
}

// =============================================================================
// Database Lifecycle
// =============================================================================

JNIEXPORT jlong JNICALL JNI_FN(nativeCreateDbInMemory)(JNIEnv* env, jobject thiz) {
    lattice_db_t* db = lattice_db_create_in_memory();
    return (jlong)(intptr_t)db;
}

JNIEXPORT jlong JNICALL JNI_FN(nativeCreateDbAtPath)(JNIEnv* env, jobject thiz, jstring path) {
    char* c_path = jstring_to_cstring(env, path);
    lattice_db_t* db = lattice_db_create_at_path(c_path);
    free(c_path);
    return (jlong)(intptr_t)db;
}

JNIEXPORT jlong JNICALL JNI_FN(nativeCreateDbWithSchemas)(JNIEnv* env, jobject thiz,
                                                          jstring path, jstring schemasJson) {
    char* c_path = jstring_to_cstring(env, path);
    // For now, ignore schemas JSON and create simple db
    // TODO: Parse schemasJson and create proper schema structures
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
    char* c_path = jstring_to_cstring(env, path);
    char* c_endpoint = jstring_to_cstring(env, syncEndpoint);
    char* c_token = jstring_to_cstring(env, authToken);

    // Create database with sync configuration
    // Pass NULL schemas for now (use createDbWithSchemaArrays for schemas)
    lattice_db_t* db = lattice_db_create_with_sync(
        c_path,
        NULL,  // schemas
        0,     // schema_count
        NULL,  // scheduler
        c_endpoint,
        c_token
    );

    free(c_path);
    free(c_endpoint);
    free(c_token);
    return (jlong)(intptr_t)db;
}

JNIEXPORT jlong JNICALL JNI_FN(nativeCreateDbWithSchemaArrays)(JNIEnv* env, jobject thiz,
                                                                jstring path,
                                                                jobjectArray tableNames,
                                                                jintArray propertyCounts,
                                                                jobjectArray propNames,
                                                                jintArray propTypes,
                                                                jintArray propKinds,
                                                                jbooleanArray propNullable,
                                                                jobjectArray propTargetTables,
                                                                jobjectArray propLinkTables,
                                                                jstring syncEndpoint,
                                                                jstring authToken) {
    char* c_path = jstring_to_cstring(env, path);
    char* c_endpoint = jstring_to_cstring(env, syncEndpoint);
    char* c_token = jstring_to_cstring(env, authToken);

    // Get number of tables
    jsize num_tables = (*env)->GetArrayLength(env, tableNames);
    if (num_tables == 0) {
        // No schemas - create simple db with sync
        lattice_db_t* db = lattice_db_create_with_sync(
            c_path, NULL, 0, NULL, c_endpoint, c_token
        );
        free(c_path);
        free(c_endpoint);
        free(c_token);
        return (jlong)(intptr_t)db;
    }

    // Get property counts per table
    jint* prop_counts = (*env)->GetIntArrayElements(env, propertyCounts, NULL);

    // Get total property count
    jsize total_props = (*env)->GetArrayLength(env, propNames);

    // Get property arrays
    jint* p_types = (*env)->GetIntArrayElements(env, propTypes, NULL);
    jint* p_kinds = (*env)->GetIntArrayElements(env, propKinds, NULL);
    jboolean* p_nullable = (*env)->GetBooleanArrayElements(env, propNullable, NULL);

    // Allocate C schema structures
    lattice_schema_t* schemas = (lattice_schema_t*)calloc(num_tables, sizeof(lattice_schema_t));
    if (!schemas) {
        (*env)->ReleaseIntArrayElements(env, propertyCounts, prop_counts, JNI_ABORT);
        (*env)->ReleaseIntArrayElements(env, propTypes, p_types, JNI_ABORT);
        (*env)->ReleaseIntArrayElements(env, propKinds, p_kinds, JNI_ABORT);
        (*env)->ReleaseBooleanArrayElements(env, propNullable, p_nullable, JNI_ABORT);
        free(c_path);
        free(c_endpoint);
        free(c_token);
        return 0;
    }

    // Build schema structures
    jsize prop_idx = 0;
    for (jsize t = 0; t < num_tables; t++) {
        // Get table name
        jstring j_table = (jstring)(*env)->GetObjectArrayElement(env, tableNames, t);
        schemas[t].table_name = jstring_to_cstring(env, j_table);
        (*env)->DeleteLocalRef(env, j_table);

        jint num_props = prop_counts[t];
        schemas[t].property_count = num_props;

        if (num_props > 0) {
            lattice_property_t* props = (lattice_property_t*)calloc(num_props, sizeof(lattice_property_t));
            schemas[t].properties = props;

            for (jint p = 0; p < num_props && prop_idx < total_props; p++, prop_idx++) {
                // Property name
                jstring j_name = (jstring)(*env)->GetObjectArrayElement(env, propNames, prop_idx);
                props[p].name = jstring_to_cstring(env, j_name);
                (*env)->DeleteLocalRef(env, j_name);

                // Type and kind
                props[p].type = (lattice_column_type_t)p_types[prop_idx];
                props[p].kind = (lattice_property_kind_t)p_kinds[prop_idx];
                props[p].nullable = p_nullable[prop_idx];

                // Target table (for links)
                jstring j_target = (jstring)(*env)->GetObjectArrayElement(env, propTargetTables, prop_idx);
                if (j_target != NULL) {
                    props[p].target_table = jstring_to_cstring(env, j_target);
                    (*env)->DeleteLocalRef(env, j_target);
                }

                // Link table (for link lists)
                jstring j_link = (jstring)(*env)->GetObjectArrayElement(env, propLinkTables, prop_idx);
                if (j_link != NULL) {
                    props[p].link_table = jstring_to_cstring(env, j_link);
                    (*env)->DeleteLocalRef(env, j_link);
                }
            }
        }
    }

    // Release Java arrays
    (*env)->ReleaseIntArrayElements(env, propertyCounts, prop_counts, JNI_ABORT);
    (*env)->ReleaseIntArrayElements(env, propTypes, p_types, JNI_ABORT);
    (*env)->ReleaseIntArrayElements(env, propKinds, p_kinds, JNI_ABORT);
    (*env)->ReleaseBooleanArrayElements(env, propNullable, p_nullable, JNI_ABORT);

    // Create database
    lattice_db_t* db;
    if (c_endpoint != NULL && c_token != NULL) {
        db = lattice_db_create_with_sync(
            c_path, schemas, (size_t)num_tables, NULL, c_endpoint, c_token
        );
    } else {
        db = lattice_db_create_with_schemas(c_path, schemas, (size_t)num_tables);
    }

    // Free schema structures
    for (jsize t = 0; t < num_tables; t++) {
        free((void*)schemas[t].table_name);
        for (size_t p = 0; p < schemas[t].property_count; p++) {
            free((void*)schemas[t].properties[p].name);
            free((void*)schemas[t].properties[p].target_table);
            free((void*)schemas[t].properties[p].link_table);
        }
        free((void*)schemas[t].properties);
    }
    free(schemas);

    free(c_path);
    free(c_endpoint);
    free(c_token);
    return (jlong)(intptr_t)db;
}

JNIEXPORT void JNICALL JNI_FN(nativeReleaseDb)(JNIEnv* env, jobject thiz, jlong dbHandle) {
    lattice_db_t* db = (lattice_db_t*)(intptr_t)dbHandle;
    if (db != NULL) {
        lattice_db_release(db);
    }
}

JNIEXPORT jstring JNICALL JNI_FN(nativeGetLastError)(JNIEnv* env, jobject thiz) {
    const char* error = lattice_last_error();
    return cstring_to_jstring(env, error);
}

// =============================================================================
// Object Operations
// =============================================================================

JNIEXPORT jlong JNICALL JNI_FN(nativeAddObject)(JNIEnv* env, jobject thiz,
                                                 jlong dbHandle, jlong objectHandle) {
    lattice_db_t* db = (lattice_db_t*)(intptr_t)dbHandle;
    lattice_object_t* obj = (lattice_object_t*)(intptr_t)objectHandle;
    if (db == NULL || obj == NULL) return 0;

    lattice_object_t* managed = lattice_db_add(db, obj);
    return (jlong)(intptr_t)managed;
}

JNIEXPORT jlong JNICALL JNI_FN(nativeFindObject)(JNIEnv* env, jobject thiz,
                                                  jlong dbHandle, jstring tableName, jlong id) {
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
    lattice_db_t* db = (lattice_db_t*)(intptr_t)dbHandle;
    if (db == NULL) return 0;

    char* c_table = jstring_to_cstring(env, tableName);
    char* c_global_id = jstring_to_cstring(env, globalId);
    lattice_object_t* obj = lattice_db_find_by_global_id(db, c_table, c_global_id);
    free(c_table);
    free(c_global_id);
    return (jlong)(intptr_t)obj;
}

JNIEXPORT jboolean JNICALL JNI_FN(nativeRemoveObject)(JNIEnv* env, jobject thiz,
                                                       jlong dbHandle, jlong objectHandle) {
    lattice_db_t* db = (lattice_db_t*)(intptr_t)dbHandle;
    lattice_object_t* obj = (lattice_object_t*)(intptr_t)objectHandle;
    if (db == NULL || obj == NULL) return JNI_FALSE;

    lattice_status_t status = lattice_db_remove(db, obj);
    return status == LATTICE_OK ? JNI_TRUE : JNI_FALSE;
}

// =============================================================================
// Query Operations
// =============================================================================

JNIEXPORT jint JNICALL JNI_FN(nativeQueryCount)(JNIEnv* env, jobject thiz,
                                                 jlong dbHandle, jstring tableName,
                                                 jstring whereClause) {
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
    lattice_db_t* db = (lattice_db_t*)(intptr_t)dbHandle;
    if (db == NULL) {
        return (*env)->NewLongArray(env, 0);
    }

    char* c_table = jstring_to_cstring(env, tableName);
    char* c_where = jstring_to_cstring(env, whereClause);
    char* c_order = jstring_to_cstring(env, orderBy);

    lattice_results_t* results = lattice_db_query(db, c_table, c_where, c_order, limit, offset);

    free(c_table);
    free(c_where);
    free(c_order);

    if (results == NULL) {
        return (*env)->NewLongArray(env, 0);
    }

    size_t count = lattice_results_count(results);
    jlongArray arr = (*env)->NewLongArray(env, (jsize)count);

    if (count > 0) {
        jlong* elements = (*env)->GetLongArrayElements(env, arr, NULL);
        for (size_t i = 0; i < count; i++) {
            lattice_object_t* obj = lattice_results_get(results, i);
            elements[i] = (jlong)(intptr_t)obj;
        }
        (*env)->ReleaseLongArrayElements(env, arr, elements, 0);
    }

    lattice_results_free(results);
    return arr;
}

JNIEXPORT jint JNICALL JNI_FN(nativeDeleteWhere)(JNIEnv* env, jobject thiz,
                                                  jlong dbHandle, jstring tableName,
                                                  jstring whereClause) {
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
    lattice_db_t* db = (lattice_db_t*)(intptr_t)dbHandle;
    if (db == NULL) return JNI_FALSE;
    return lattice_db_begin_transaction(db) == LATTICE_OK ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL JNI_FN(nativeCommitTransaction)(JNIEnv* env, jobject thiz, jlong dbHandle) {
    lattice_db_t* db = (lattice_db_t*)(intptr_t)dbHandle;
    if (db == NULL) return JNI_FALSE;
    return lattice_db_commit(db) == LATTICE_OK ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL JNI_FN(nativeRollbackTransaction)(JNIEnv* env, jobject thiz, jlong dbHandle) {
    lattice_db_t* db = (lattice_db_t*)(intptr_t)dbHandle;
    if (db != NULL) {
        lattice_db_rollback(db);
    }
}

// =============================================================================
// Object Property Access
// =============================================================================

JNIEXPORT jlong JNICALL JNI_FN(nativeCreateObject)(JNIEnv* env, jobject thiz,
                                                    jstring tableName, jstring schemaJson) {
    char* c_table = jstring_to_cstring(env, tableName);
    lattice_object_t* obj = lattice_object_create(c_table);
    free(c_table);
    return (jlong)(intptr_t)obj;
}

JNIEXPORT jlong JNICALL JNI_FN(nativeGetIntProperty)(JNIEnv* env, jobject thiz,
                                                      jlong objectHandle, jstring propertyName) {
    lattice_object_t* obj = (lattice_object_t*)(intptr_t)objectHandle;
    if (obj == NULL) return 0;

    char* c_prop = jstring_to_cstring(env, propertyName);
    int64_t value = lattice_object_get_int(obj, c_prop);
    free(c_prop);
    return (jlong)value;
}

JNIEXPORT void JNICALL JNI_FN(nativeSetIntProperty)(JNIEnv* env, jobject thiz,
                                                     jlong objectHandle, jstring propertyName,
                                                     jlong value) {
    lattice_object_t* obj = (lattice_object_t*)(intptr_t)objectHandle;
    if (obj == NULL) return;

    char* c_prop = jstring_to_cstring(env, propertyName);
    lattice_object_set_int(obj, c_prop, value);
    free(c_prop);
}

JNIEXPORT jdouble JNICALL JNI_FN(nativeGetDoubleProperty)(JNIEnv* env, jobject thiz,
                                                          jlong objectHandle, jstring propertyName) {
    lattice_object_t* obj = (lattice_object_t*)(intptr_t)objectHandle;
    if (obj == NULL) return 0.0;

    char* c_prop = jstring_to_cstring(env, propertyName);
    double value = lattice_object_get_double(obj, c_prop);
    free(c_prop);
    return value;
}

JNIEXPORT void JNICALL JNI_FN(nativeSetDoubleProperty)(JNIEnv* env, jobject thiz,
                                                        jlong objectHandle, jstring propertyName,
                                                        jdouble value) {
    lattice_object_t* obj = (lattice_object_t*)(intptr_t)objectHandle;
    if (obj == NULL) return;

    char* c_prop = jstring_to_cstring(env, propertyName);
    lattice_object_set_double(obj, c_prop, value);
    free(c_prop);
}

JNIEXPORT jstring JNICALL JNI_FN(nativeGetStringProperty)(JNIEnv* env, jobject thiz,
                                                          jlong objectHandle, jstring propertyName) {
    lattice_object_t* obj = (lattice_object_t*)(intptr_t)objectHandle;
    if (obj == NULL) return NULL;

    char* c_prop = jstring_to_cstring(env, propertyName);
    const char* value = lattice_object_get_string(obj, c_prop);
    free(c_prop);
    return cstring_to_jstring(env, value);
}

JNIEXPORT void JNICALL JNI_FN(nativeSetStringProperty)(JNIEnv* env, jobject thiz,
                                                        jlong objectHandle, jstring propertyName,
                                                        jstring value) {
    lattice_object_t* obj = (lattice_object_t*)(intptr_t)objectHandle;
    if (obj == NULL) return;

    char* c_prop = jstring_to_cstring(env, propertyName);
    char* c_value = jstring_to_cstring(env, value);
    if (c_value != NULL) {
        lattice_object_set_string(obj, c_prop, c_value);
        free(c_value);
    } else {
        lattice_object_set_null(obj, c_prop);
    }
    free(c_prop);
}

JNIEXPORT jboolean JNICALL JNI_FN(nativeGetBoolProperty)(JNIEnv* env, jobject thiz,
                                                         jlong objectHandle, jstring propertyName) {
    lattice_object_t* obj = (lattice_object_t*)(intptr_t)objectHandle;
    if (obj == NULL) return JNI_FALSE;

    char* c_prop = jstring_to_cstring(env, propertyName);
    int64_t value = lattice_object_get_int(obj, c_prop);
    free(c_prop);
    return value != 0 ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL JNI_FN(nativeSetBoolProperty)(JNIEnv* env, jobject thiz,
                                                      jlong objectHandle, jstring propertyName,
                                                      jboolean value) {
    lattice_object_t* obj = (lattice_object_t*)(intptr_t)objectHandle;
    if (obj == NULL) return;

    char* c_prop = jstring_to_cstring(env, propertyName);
    lattice_object_set_int(obj, c_prop, value ? 1 : 0);
    free(c_prop);
}

JNIEXPORT jlong JNICALL JNI_FN(nativeGetObjectId)(JNIEnv* env, jobject thiz, jlong objectHandle) {
    lattice_object_t* obj = (lattice_object_t*)(intptr_t)objectHandle;
    if (obj == NULL) return 0;
    return lattice_object_get_id(obj);
}

JNIEXPORT jstring JNICALL JNI_FN(nativeGetObjectGlobalId)(JNIEnv* env, jobject thiz,
                                                          jlong objectHandle) {
    lattice_object_t* obj = (lattice_object_t*)(intptr_t)objectHandle;
    if (obj == NULL) return NULL;
    return cstring_to_jstring(env, lattice_object_get_global_id(obj));
}

JNIEXPORT jboolean JNICALL JNI_FN(nativeHasValue)(JNIEnv* env, jobject thiz,
                                                   jlong objectHandle, jstring propertyName) {
    lattice_object_t* obj = (lattice_object_t*)(intptr_t)objectHandle;
    if (obj == NULL) return JNI_FALSE;

    char* c_prop = jstring_to_cstring(env, propertyName);
    bool has = lattice_object_has_value(obj, c_prop);
    free(c_prop);
    return has ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL JNI_FN(nativeSetNull)(JNIEnv* env, jobject thiz,
                                              jlong objectHandle, jstring propertyName) {
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
    lattice_object_t* obj = (lattice_object_t*)(intptr_t)objectHandle;
    if (obj == NULL) return;

    char* c_prop = jstring_to_cstring(env, propertyName);

    // linkedHandle is boxed Long or null
    if (linkedHandle == NULL) {
        lattice_object_set_null(obj, c_prop);
    } else {
        // Get the Long value
        jclass longClass = (*env)->FindClass(env, "java/lang/Long");
        jmethodID longValue = (*env)->GetMethodID(env, longClass, "longValue", "()J");
        jlong linked = (*env)->CallLongMethod(env, linkedHandle, longValue);

        if (linked != 0) {
            lattice_object_t* linked_obj = (lattice_object_t*)(intptr_t)linked;
            lattice_object_set_object(obj, c_prop, linked_obj);
        } else {
            lattice_object_set_null(obj, c_prop);
        }
    }

    free(c_prop);
}

JNIEXPORT jlong JNICALL JNI_FN(nativeGetLinkListHandle)(JNIEnv* env, jobject thiz,
                                                         jlong objectHandle, jstring propertyName) {
    lattice_object_t* obj = (lattice_object_t*)(intptr_t)objectHandle;
    if (obj == NULL) return 0;

    char* c_prop = jstring_to_cstring(env, propertyName);
    lattice_link_list_t* list = lattice_object_get_link_list(obj, c_prop);
    free(c_prop);
    return (jlong)(intptr_t)list;
}

// =============================================================================
// Object Creation
// =============================================================================

JNIEXPORT jlong JNICALL JNI_FN(nativeCreateObjectWithSchema)(JNIEnv* env, jobject thiz,
                                                              jstring tableName, jstring schemaJson) {
    char* c_table = jstring_to_cstring(env, tableName);
    // TODO: Parse schemaJson and create with proper schema
    lattice_object_t* obj = lattice_object_create(c_table);
    free(c_table);
    return (jlong)(intptr_t)obj;
}

// =============================================================================
// Sync Operations
// =============================================================================

JNIEXPORT jstring JNICALL JNI_FN(nativeReceiveSyncData)(JNIEnv* env, jobject thiz,
                                                         jlong dbHandle, jbyteArray data) {
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
    lattice_db_t* db = (lattice_db_t*)(intptr_t)dbHandle;
    if (db == NULL) return NULL;

    char* c_global_id = jstring_to_cstring(env, globalId);
    char* result = lattice_db_events_after(db, c_global_id);
    free(c_global_id);

    if (result == NULL) return NULL;
    jstring jresult = cstring_to_jstring(env, result);
    lattice_string_free(result);
    return jresult;
}

JNIEXPORT jstring JNICALL JNI_FN(nativeGetPendingEvents)(JNIEnv* env, jobject thiz, jlong dbHandle) {
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
    lattice_db_t* db = (lattice_db_t*)(intptr_t)dbHandle;
    if (db == NULL) return;

    char* c_json = jstring_to_cstring(env, globalIdsJson);
    lattice_db_mark_synced(db, c_json);
    free(c_json);
}

// =============================================================================
// Results Operations
// =============================================================================

JNIEXPORT jint JNICALL JNI_FN(nativeResultsCount)(JNIEnv* env, jobject thiz, jlong resultsHandle) {
    lattice_results_t* results = (lattice_results_t*)(intptr_t)resultsHandle;
    if (results == NULL) return 0;
    return (jint)lattice_results_count(results);
}

JNIEXPORT jlong JNICALL JNI_FN(nativeResultsGet)(JNIEnv* env, jobject thiz,
                                                  jlong resultsHandle, jint index) {
    lattice_results_t* results = (lattice_results_t*)(intptr_t)resultsHandle;
    if (results == NULL) return 0;
    lattice_object_t* obj = lattice_results_get(results, (size_t)index);
    return (jlong)(intptr_t)obj;
}

JNIEXPORT void JNICALL JNI_FN(nativeResultsFree)(JNIEnv* env, jobject thiz, jlong resultsHandle) {
    lattice_results_t* results = (lattice_results_t*)(intptr_t)resultsHandle;
    if (results != NULL) {
        lattice_results_free(results);
    }
}

// =============================================================================
// Link List Operations
// =============================================================================

JNIEXPORT jint JNICALL JNI_FN(nativeLinkListCount)(JNIEnv* env, jobject thiz, jlong listHandle) {
    lattice_link_list_t* list = (lattice_link_list_t*)(intptr_t)listHandle;
    if (list == NULL) return 0;
    return (jint)lattice_link_list_size(list);
}

JNIEXPORT jlong JNICALL JNI_FN(nativeLinkListGet)(JNIEnv* env, jobject thiz,
                                                   jlong listHandle, jint index) {
    lattice_link_list_t* list = (lattice_link_list_t*)(intptr_t)listHandle;
    if (list == NULL) return 0;
    lattice_object_t* obj = lattice_link_list_get(list, (size_t)index);
    return (jlong)(intptr_t)obj;
}

JNIEXPORT void JNICALL JNI_FN(nativeLinkListAppend)(JNIEnv* env, jobject thiz,
                                                     jlong listHandle, jlong objectHandle) {
    lattice_link_list_t* list = (lattice_link_list_t*)(intptr_t)listHandle;
    lattice_object_t* obj = (lattice_object_t*)(intptr_t)objectHandle;
    if (list == NULL || obj == NULL) return;
    lattice_link_list_push_back(list, obj);
}

JNIEXPORT void JNICALL JNI_FN(nativeLinkListInsert)(JNIEnv* env, jobject thiz,
                                                     jlong listHandle, jint index,
                                                     jlong objectHandle) {
    lattice_link_list_t* list = (lattice_link_list_t*)(intptr_t)listHandle;
    lattice_object_t* obj = (lattice_object_t*)(intptr_t)objectHandle;
    if (list == NULL || obj == NULL) return;
    // C API doesn't have insert, so just append
    lattice_link_list_push_back(list, obj);
}

JNIEXPORT void JNICALL JNI_FN(nativeLinkListRemove)(JNIEnv* env, jobject thiz,
                                                     jlong listHandle, jint index) {
    lattice_link_list_t* list = (lattice_link_list_t*)(intptr_t)listHandle;
    if (list == NULL) return;
    lattice_link_list_erase(list, (size_t)index);
}

JNIEXPORT void JNICALL JNI_FN(nativeLinkListClear)(JNIEnv* env, jobject thiz, jlong listHandle) {
    lattice_link_list_t* list = (lattice_link_list_t*)(intptr_t)listHandle;
    if (list == NULL) return;
    lattice_link_list_clear(list);
}

// =============================================================================
// String Memory
// =============================================================================

JNIEXPORT void JNICALL JNI_FN(nativeFreeString)(JNIEnv* env, jobject thiz, jlong ptr) {
    char* str = (char*)(intptr_t)ptr;
    if (str != NULL) {
        lattice_string_free(str);
    }
}

// =============================================================================
// Observer Operations
// =============================================================================

// C callback that invokes the Kotlin callback
static void table_observer_callback(void* context, const char* operation,
                                     int64_t row_id, const char* global_id) {
    __android_log_print(ANDROID_LOG_DEBUG, "LatticeJNI",
        "table_observer_callback: context=%p, operation=%s, row_id=%lld, global_id=%s",
        context, operation ? operation : "null", (long long)row_id, global_id ? global_id : "null");

    if (context == NULL) {
        __android_log_print(ANDROID_LOG_ERROR, "LatticeJNI", "table_observer_callback: context is NULL");
        return;
    }
    if (g_jvm == NULL) {
        __android_log_print(ANDROID_LOG_ERROR, "LatticeJNI", "table_observer_callback: g_jvm is NULL");
        return;
    }

    observer_context_t* ctx = (observer_context_t*)context;

    // Validate context pointer looks reasonable
    if ((uintptr_t)ctx < 0x1000) {
        __android_log_print(ANDROID_LOG_ERROR, "LatticeJNI",
            "table_observer_callback: context pointer looks invalid: %p", ctx);
        return;
    }

    __android_log_print(ANDROID_LOG_DEBUG, "LatticeJNI",
        "table_observer_callback: ctx->callback=%p, ctx->invokeMethod=%p, ctx->token=%llu",
        ctx->callback, ctx->invokeMethod, (unsigned long long)ctx->token);

    // Validate callback reference
    if (ctx->callback == NULL) {
        __android_log_print(ANDROID_LOG_ERROR, "LatticeJNI", "table_observer_callback: ctx->callback is NULL");
        return;
    }
    if ((uintptr_t)ctx->callback < 0x1000) {
        __android_log_print(ANDROID_LOG_ERROR, "LatticeJNI",
            "table_observer_callback: ctx->callback looks invalid: %p", ctx->callback);
        return;
    }
    if (ctx->invokeMethod == NULL) {
        __android_log_print(ANDROID_LOG_ERROR, "LatticeJNI", "table_observer_callback: ctx->invokeMethod is NULL");
        return;
    }

    JNIEnv* env = NULL;
    int needs_detach = 0;

    // Get JNIEnv for this thread
    int status = (*g_jvm)->GetEnv(g_jvm, (void**)&env, JNI_VERSION_1_6);
    __android_log_print(ANDROID_LOG_DEBUG, "LatticeJNI", "table_observer_callback: GetEnv status=%d", status);

    if (status == JNI_EDETACHED) {
        // Attach current thread to JVM
        __android_log_print(ANDROID_LOG_DEBUG, "LatticeJNI", "table_observer_callback: attaching thread to JVM");
        if ((*g_jvm)->AttachCurrentThread(g_jvm, &env, NULL) != 0) {
            __android_log_print(ANDROID_LOG_ERROR, "LatticeJNI", "table_observer_callback: failed to attach thread");
            return;
        }
        needs_detach = 1;
    } else if (status != JNI_OK) {
        __android_log_print(ANDROID_LOG_ERROR, "LatticeJNI", "table_observer_callback: GetEnv failed with status %d", status);
        return;
    }

    // Verify the global reference is still valid
    jobjectRefType refType = (*env)->GetObjectRefType(env, ctx->callback);
    __android_log_print(ANDROID_LOG_DEBUG, "LatticeJNI",
        "table_observer_callback: callback refType=%d (0=invalid, 1=local, 2=global, 3=weak)", refType);

    if (refType != JNIGlobalRefType) {
        __android_log_print(ANDROID_LOG_ERROR, "LatticeJNI",
            "table_observer_callback: callback is not a global reference (type=%d), skipping callback", refType);
        if (needs_detach) {
            (*g_jvm)->DetachCurrentThread(g_jvm);
        }
        return;
    }

    // Call the Kotlin callback: invoke(operation: String, rowId: Long, globalId: String?)
    jstring j_operation = cstring_to_jstring(env, operation);
    jstring j_global_id = cstring_to_jstring(env, global_id);

    // Box the row_id as a java.lang.Long object (required for Function3 which takes Objects)
    jclass longClass = (*env)->FindClass(env, "java/lang/Long");
    jmethodID longValueOf = (*env)->GetStaticMethodID(env, longClass, "valueOf", "(J)Ljava/lang/Long;");
    jobject j_row_id = (*env)->CallStaticObjectMethod(env, longClass, longValueOf, (jlong)row_id);

    __android_log_print(ANDROID_LOG_DEBUG, "LatticeJNI",
        "table_observer_callback: calling invoke with j_operation=%p, j_row_id=%p, j_global_id=%p",
        j_operation, j_row_id, j_global_id);

    (*env)->CallObjectMethod(env, ctx->callback, ctx->invokeMethod,
                           j_operation, j_row_id, j_global_id);

    __android_log_print(ANDROID_LOG_DEBUG, "LatticeJNI", "table_observer_callback: invoke returned");

    // Clean up local references
    if (j_operation != NULL) (*env)->DeleteLocalRef(env, j_operation);
    if (j_row_id != NULL) (*env)->DeleteLocalRef(env, j_row_id);
    if (j_global_id != NULL) (*env)->DeleteLocalRef(env, j_global_id);
    if (longClass != NULL) (*env)->DeleteLocalRef(env, longClass);

    // Check for exceptions
    if ((*env)->ExceptionCheck(env)) {
        __android_log_print(ANDROID_LOG_ERROR, "LatticeJNI", "table_observer_callback: exception occurred");
        (*env)->ExceptionClear(env);
    }

    if (needs_detach) {
        (*g_jvm)->DetachCurrentThread(g_jvm);
    }

    __android_log_print(ANDROID_LOG_DEBUG, "LatticeJNI", "table_observer_callback: done");
}

JNIEXPORT jlong JNICALL JNI_FN(nativeObserveTable)(JNIEnv* env, jobject thiz,
                                                    jlong dbHandle, jstring tableName,
                                                    jobject callback) {
    lattice_db_t* db = (lattice_db_t*)(intptr_t)dbHandle;

    char* c_table = jstring_to_cstring(env, tableName);
    __android_log_print(ANDROID_LOG_DEBUG, "LatticeJNI",
        "nativeObserveTable: db=%p, table=%s, callback=%p",
        db, c_table ? c_table : "null", callback);

    if (db == NULL || callback == NULL) {
        __android_log_print(ANDROID_LOG_ERROR, "LatticeJNI", "nativeObserveTable: db or callback is NULL");
        if (c_table) free(c_table);
        return 0;
    }

    // Create observer context
    observer_context_t* ctx = (observer_context_t*)malloc(sizeof(observer_context_t));
    if (ctx == NULL) {
        __android_log_print(ANDROID_LOG_ERROR, "LatticeJNI", "nativeObserveTable: failed to allocate context");
        free(c_table);
        return 0;
    }

    // Create global reference to the callback
    ctx->callback = (*env)->NewGlobalRef(env, callback);
    __android_log_print(ANDROID_LOG_DEBUG, "LatticeJNI",
        "nativeObserveTable: created global ref for callback: %p -> %p",
        callback, ctx->callback);

    if (ctx->callback == NULL) {
        __android_log_print(ANDROID_LOG_ERROR, "LatticeJNI", "nativeObserveTable: NewGlobalRef failed");
        free(ctx);
        free(c_table);
        return 0;
    }

    // Get the invoke method - the callback is a Function3<String, Long, String?, Unit>
    jclass callbackClass = (*env)->GetObjectClass(env, callback);
    ctx->invokeMethod = (*env)->GetMethodID(env, callbackClass, "invoke",
        "(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;");

    __android_log_print(ANDROID_LOG_DEBUG, "LatticeJNI",
        "nativeObserveTable: invokeMethod=%p", ctx->invokeMethod);

    if (ctx->invokeMethod == NULL) {
        __android_log_print(ANDROID_LOG_ERROR, "LatticeJNI", "nativeObserveTable: failed to get invoke method");
        (*env)->DeleteGlobalRef(env, ctx->callback);
        free(ctx);
        free(c_table);
        return 0;
    }

    // Register the observer with the C API
    __android_log_print(ANDROID_LOG_DEBUG, "LatticeJNI",
        "nativeObserveTable: calling lattice_db_observe_table with ctx=%p", ctx);
    uint64_t token = lattice_db_observe_table(db, c_table, ctx, table_observer_callback);

    free(c_table);

    if (token == 0) {
        __android_log_print(ANDROID_LOG_ERROR, "LatticeJNI", "nativeObserveTable: lattice_db_observe_table returned 0");
        (*env)->DeleteGlobalRef(env, ctx->callback);
        free(ctx);
        return 0;
    }

    // Store token in context for later removal
    ctx->token = token;

    __android_log_print(ANDROID_LOG_DEBUG, "LatticeJNI",
        "nativeObserveTable: SUCCESS - ctx=%p, callback=%p, invokeMethod=%p, token=%llu",
        ctx, ctx->callback, ctx->invokeMethod, (unsigned long long)token);

    // Return the context pointer as a handle (includes token for cleanup)
    return (jlong)(intptr_t)ctx;
}

JNIEXPORT void JNICALL JNI_FN(nativeRemoveTableObserver)(JNIEnv* env, jobject thiz,
                                                          jlong dbHandle, jstring tableName,
                                                          jlong contextHandle) {
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
// Network Factory and WebSocket Bridge
// =============================================================================

// Context for WebSocket bridge - holds references to Kotlin callbacks
typedef struct {
    jobject websocket;      // Global reference to Kotlin LatticeWebSocket
    jmethodID connectMethod;
    jmethodID disconnectMethod;
    jmethodID stateMethod;
    jmethodID sendMethod;
    lattice_websocket_client_t* client;
} ws_bridge_context_t;

// Global map from client pointer to context (simple array for now)
#define MAX_WS_CLIENTS 16
static struct {
    lattice_websocket_client_t* client;
    ws_bridge_context_t* ctx;
} g_ws_client_map[MAX_WS_CLIENTS];
static pthread_mutex_t g_ws_client_map_mutex = PTHREAD_MUTEX_INITIALIZER;

static void register_ws_client(lattice_websocket_client_t* client, ws_bridge_context_t* ctx) {
    pthread_mutex_lock(&g_ws_client_map_mutex);
    for (int i = 0; i < MAX_WS_CLIENTS; i++) {
        if (g_ws_client_map[i].client == NULL) {
            g_ws_client_map[i].client = client;
            g_ws_client_map[i].ctx = ctx;
            __android_log_print(ANDROID_LOG_DEBUG, "LatticeJNI",
                "Registered ws client %p -> ctx %p (websocket=%p)",
                (void*)client, (void*)ctx, (void*)ctx->websocket);
            pthread_mutex_unlock(&g_ws_client_map_mutex);
            return;
        }
    }
    __android_log_print(ANDROID_LOG_ERROR, "LatticeJNI", "No room for new ws client!");
    pthread_mutex_unlock(&g_ws_client_map_mutex);
}

static ws_bridge_context_t* lookup_ws_client(lattice_websocket_client_t* client) {
    pthread_mutex_lock(&g_ws_client_map_mutex);
    for (int i = 0; i < MAX_WS_CLIENTS; i++) {
        if (g_ws_client_map[i].client == client) {
            ws_bridge_context_t* ctx = g_ws_client_map[i].ctx;
            pthread_mutex_unlock(&g_ws_client_map_mutex);
            return ctx;
        }
    }
    pthread_mutex_unlock(&g_ws_client_map_mutex);
    return NULL;
}

// Context for factory bridge
typedef struct {
    jobject factory;        // Global reference to Kotlin LatticeWebSocketFactory
    jmethodID createMethod;
} factory_bridge_context_t;

// Helper function to find context from user_data with fallbacks
static ws_bridge_context_t* find_ws_context(void* user_data, const char* caller) {
    ws_bridge_context_t* ctx = NULL;

    // First try to use user_data as context directly
    if (user_data != NULL && (uintptr_t)user_data > 0x1000) {
        ws_bridge_context_t* maybe_ctx = (ws_bridge_context_t*)user_data;
        // Validate by checking if the websocket looks like a valid pointer
        if ((uintptr_t)maybe_ctx->websocket > 0x1000) {
            ctx = maybe_ctx;
            __android_log_print(ANDROID_LOG_DEBUG, "LatticeJNI",
                "%s: using user_data as context, websocket=%p", caller, (void*)ctx->websocket);
        }
    }

    // If user_data didn't work, try looking up by treating user_data as client pointer
    if (ctx == NULL) {
        ctx = lookup_ws_client((lattice_websocket_client_t*)user_data);
        if (ctx != NULL) {
            __android_log_print(ANDROID_LOG_DEBUG, "LatticeJNI",
                "%s: found context via lookup, websocket=%p", caller, (void*)ctx->websocket);
        }
    }

    // Last resort: find any registered context (only if we have exactly one)
    if (ctx == NULL) {
        pthread_mutex_lock(&g_ws_client_map_mutex);
        int count = 0;
        for (int i = 0; i < MAX_WS_CLIENTS; i++) {
            if (g_ws_client_map[i].ctx != NULL) {
                ctx = g_ws_client_map[i].ctx;
                count++;
            }
        }
        pthread_mutex_unlock(&g_ws_client_map_mutex);
        if (count != 1) {
            ctx = NULL; // Only use if exactly one client
        }
        if (ctx != NULL) {
            __android_log_print(ANDROID_LOG_DEBUG, "LatticeJNI",
                "%s: using single registered context, websocket=%p", caller, (void*)ctx->websocket);
        }
    }

    return ctx;
}

// WebSocket callbacks called by C++ layer
static void ws_connect_callback(void* user_data, const char* url, const char* headers_json) {
    __android_log_print(ANDROID_LOG_DEBUG, "LatticeJNI",
        "ws_connect_callback: user_data=%p, url=%s", user_data, url ? url : "null");

    if (g_jvm == NULL) return;

    ws_bridge_context_t* ctx = find_ws_context(user_data, "ws_connect_callback");
    if (ctx == NULL || ctx->websocket == NULL) {
        __android_log_print(ANDROID_LOG_ERROR, "LatticeJNI",
            "ws_connect_callback: could not find valid context");
        return;
    }

    JNIEnv* env = NULL;
    int needs_detach = 0;
    int status = (*g_jvm)->GetEnv(g_jvm, (void**)&env, JNI_VERSION_1_6);
    if (status == JNI_EDETACHED) {
        if ((*g_jvm)->AttachCurrentThread(g_jvm, &env, NULL) != 0) return;
        needs_detach = 1;
    } else if (status != JNI_OK) return;

    jstring j_url = cstring_to_jstring(env, url);
    jstring j_headers = cstring_to_jstring(env, headers_json ? headers_json : "{}");

    (*env)->CallVoidMethod(env, ctx->websocket, ctx->connectMethod, j_url, j_headers);

    if (j_url) (*env)->DeleteLocalRef(env, j_url);
    if (j_headers) (*env)->DeleteLocalRef(env, j_headers);
    if ((*env)->ExceptionCheck(env)) (*env)->ExceptionClear(env);
    if (needs_detach) (*g_jvm)->DetachCurrentThread(g_jvm);
}

static void ws_disconnect_callback(void* user_data) {
    if (user_data == NULL || g_jvm == NULL) return;
    ws_bridge_context_t* ctx = (ws_bridge_context_t*)user_data;

    JNIEnv* env = NULL;
    int needs_detach = 0;
    int status = (*g_jvm)->GetEnv(g_jvm, (void**)&env, JNI_VERSION_1_6);
    if (status == JNI_EDETACHED) {
        if ((*g_jvm)->AttachCurrentThread(g_jvm, &env, NULL) != 0) return;
        needs_detach = 1;
    } else if (status != JNI_OK) return;

    (*env)->CallVoidMethod(env, ctx->websocket, ctx->disconnectMethod);

    if ((*env)->ExceptionCheck(env)) (*env)->ExceptionClear(env);
    if (needs_detach) (*g_jvm)->DetachCurrentThread(g_jvm);
}

static lattice_websocket_state_t ws_state_callback(void* user_data) {
    if (user_data == NULL || g_jvm == NULL) return LATTICE_WS_CLOSED;
    ws_bridge_context_t* ctx = (ws_bridge_context_t*)user_data;

    JNIEnv* env = NULL;
    int needs_detach = 0;
    int status = (*g_jvm)->GetEnv(g_jvm, (void**)&env, JNI_VERSION_1_6);
    if (status == JNI_EDETACHED) {
        if ((*g_jvm)->AttachCurrentThread(g_jvm, &env, NULL) != 0) return LATTICE_WS_CLOSED;
        needs_detach = 1;
    } else if (status != JNI_OK) return LATTICE_WS_CLOSED;

    jint state = (*env)->CallIntMethod(env, ctx->websocket, ctx->stateMethod);

    if ((*env)->ExceptionCheck(env)) {
        (*env)->ExceptionClear(env);
        state = 3; // CLOSED
    }
    if (needs_detach) (*g_jvm)->DetachCurrentThread(g_jvm);

    // Map Kotlin enum ordinal to C enum
    switch (state) {
        case 0: return LATTICE_WS_CONNECTING;
        case 1: return LATTICE_WS_OPEN;
        case 2: return LATTICE_WS_CLOSING;
        default: return LATTICE_WS_CLOSED;
    }
}

static void ws_send_callback(void* user_data, lattice_websocket_msg_type_t type,
                             const uint8_t* data, size_t data_size) {
    // CRITICAL: Log immediately before any pointer access
    __android_log_print(ANDROID_LOG_WARN, "LatticeJNI",
        ">>> ws_send_callback ENTRY: user_data=%p, type=%d, size=%zu", user_data, (int)type, data_size);

    if (g_jvm == NULL) {
        __android_log_print(ANDROID_LOG_ERROR, "LatticeJNI", "ws_send_callback: g_jvm is NULL!");
        return;
    }

    ws_bridge_context_t* ctx = find_ws_context(user_data, "ws_send_callback");

    __android_log_print(ANDROID_LOG_WARN, "LatticeJNI",
        ">>> ws_send_callback: found ctx=%p", (void*)ctx);

    if (ctx == NULL) {
        __android_log_print(ANDROID_LOG_ERROR, "LatticeJNI",
            "ws_send_callback: could not find valid context for user_data=%p", user_data);
        return;
    }

    __android_log_print(ANDROID_LOG_WARN, "LatticeJNI",
        ">>> ws_send_callback: ctx->websocket=%p, ctx->sendMethod=%p",
        (void*)ctx->websocket, (void*)ctx->sendMethod);

    if (ctx->websocket == NULL) {
        __android_log_print(ANDROID_LOG_ERROR, "LatticeJNI",
            "ws_send_callback: websocket is NULL");
        return;
    }

    JNIEnv* env = NULL;
    int needs_detach = 0;
    int status = (*g_jvm)->GetEnv(g_jvm, (void**)&env, JNI_VERSION_1_6);
    if (status == JNI_EDETACHED) {
        if ((*g_jvm)->AttachCurrentThread(g_jvm, &env, NULL) != 0) return;
        needs_detach = 1;
    } else if (status != JNI_OK) return;

    jint j_type = (type == LATTICE_WS_MSG_TEXT) ? 0 : 1;
    jbyteArray j_data = (*env)->NewByteArray(env, (jsize)data_size);
    if (j_data && data_size > 0) {
        (*env)->SetByteArrayRegion(env, j_data, 0, (jsize)data_size, (const jbyte*)data);
    }

    __android_log_print(ANDROID_LOG_WARN, "LatticeJNI",
        ">>> ws_send_callback: ABOUT TO CallVoidMethod websocket=%p sendMethod=%p j_type=%d j_data=%p",
        (void*)ctx->websocket, (void*)ctx->sendMethod, j_type, (void*)j_data);

    (*env)->CallVoidMethod(env, ctx->websocket, ctx->sendMethod, j_type, j_data);

    __android_log_print(ANDROID_LOG_WARN, "LatticeJNI", ">>> ws_send_callback: CallVoidMethod returned");

    if (j_data) (*env)->DeleteLocalRef(env, j_data);
    if ((*env)->ExceptionCheck(env)) (*env)->ExceptionClear(env);
    if (needs_detach) (*g_jvm)->DetachCurrentThread(g_jvm);
}

// Factory callback - creates a new WebSocket client
static lattice_websocket_client_t* factory_create_ws_callback(void* user_data) {
    if (user_data == NULL || g_jvm == NULL) return NULL;
    factory_bridge_context_t* factory_ctx = (factory_bridge_context_t*)user_data;

    JNIEnv* env = NULL;
    int needs_detach = 0;
    int status = (*g_jvm)->GetEnv(g_jvm, (void**)&env, JNI_VERSION_1_6);
    if (status == JNI_EDETACHED) {
        if ((*g_jvm)->AttachCurrentThread(g_jvm, &env, NULL) != 0) return NULL;
        needs_detach = 1;
    } else if (status != JNI_OK) return NULL;

    // Call Kotlin factory to create WebSocket
    jobject ws = (*env)->CallObjectMethod(env, factory_ctx->factory, factory_ctx->createMethod);
    if (ws == NULL || (*env)->ExceptionCheck(env)) {
        (*env)->ExceptionClear(env);
        if (needs_detach) (*g_jvm)->DetachCurrentThread(g_jvm);
        return NULL;
    }

    // Create bridge context
    ws_bridge_context_t* ws_ctx = (ws_bridge_context_t*)malloc(sizeof(ws_bridge_context_t));
    if (ws_ctx == NULL) {
        (*env)->DeleteLocalRef(env, ws);
        if (needs_detach) (*g_jvm)->DetachCurrentThread(g_jvm);
        return NULL;
    }

    ws_ctx->websocket = (*env)->NewGlobalRef(env, ws);
    (*env)->DeleteLocalRef(env, ws);

    if (ws_ctx->websocket == NULL) {
        __android_log_print(ANDROID_LOG_ERROR, "LatticeJNI", "NewGlobalRef failed for websocket");
        free(ws_ctx);
        if (needs_detach) (*g_jvm)->DetachCurrentThread(g_jvm);
        return NULL;
    }

    __android_log_print(ANDROID_LOG_DEBUG, "LatticeJNI",
        "factory_create_ws_callback: ws_ctx=%p, websocket=%p",
        (void*)ws_ctx, (void*)ws_ctx->websocket);

    // Get method IDs
    jclass wsClass = (*env)->GetObjectClass(env, ws_ctx->websocket);
    ws_ctx->connectMethod = (*env)->GetMethodID(env, wsClass, "connectWithHeaders",
                                                 "(Ljava/lang/String;Ljava/lang/String;)V");
    ws_ctx->disconnectMethod = (*env)->GetMethodID(env, wsClass, "disconnect", "()V");
    ws_ctx->stateMethod = (*env)->GetMethodID(env, wsClass, "stateOrdinal", "()I");
    ws_ctx->sendMethod = (*env)->GetMethodID(env, wsClass, "sendBytes", "(I[B)V");

    // Create C WebSocket client
    ws_ctx->client = lattice_websocket_client_create(
        ws_ctx,
        ws_connect_callback,
        ws_disconnect_callback,
        ws_state_callback,
        ws_send_callback
    );

    __android_log_print(ANDROID_LOG_DEBUG, "LatticeJNI",
        "factory_create_ws_callback: client created=%p, ws_ctx=%p still has websocket=%p",
        (void*)ws_ctx->client, (void*)ws_ctx, (void*)ws_ctx->websocket);

    // Register the client->context mapping for lookup in callbacks
    register_ws_client(ws_ctx->client, ws_ctx);

    // Pass the client handle back to the Kotlin wrapper so it can trigger callbacks
    jmethodID setHandleMethod = (*env)->GetMethodID(env, wsClass, "setClientHandle", "(J)V");
    if (setHandleMethod != NULL) {
        (*env)->CallVoidMethod(env, ws_ctx->websocket, setHandleMethod,
                               (jlong)(intptr_t)ws_ctx->client);
    }

    if (needs_detach) (*g_jvm)->DetachCurrentThread(g_jvm);
    return ws_ctx->client;
}

static void factory_destroy_callback(void* user_data) {
    if (user_data == NULL) return;
    factory_bridge_context_t* ctx = (factory_bridge_context_t*)user_data;

    if (g_jvm != NULL && ctx->factory != NULL) {
        JNIEnv* env = NULL;
        int needs_detach = 0;
        int status = (*g_jvm)->GetEnv(g_jvm, (void**)&env, JNI_VERSION_1_6);
        if (status == JNI_EDETACHED) {
            if ((*g_jvm)->AttachCurrentThread(g_jvm, &env, NULL) == 0) {
                needs_detach = 1;
            }
        }
        if (env != NULL) {
            (*env)->DeleteGlobalRef(env, ctx->factory);
            if (needs_detach) (*g_jvm)->DetachCurrentThread(g_jvm);
        }
    }
    free(ctx);
}

JNIEXPORT jlong JNICALL JNI_FN(nativeCreateNetworkFactory)(JNIEnv* env, jobject thiz,
                                                            jobject factory) {
    if (factory == NULL) return 0;

    factory_bridge_context_t* ctx = (factory_bridge_context_t*)malloc(sizeof(factory_bridge_context_t));
    if (ctx == NULL) return 0;

    ctx->factory = (*env)->NewGlobalRef(env, factory);

    jclass factoryClass = (*env)->GetObjectClass(env, factory);
    ctx->createMethod = (*env)->GetMethodID(env, factoryClass, "createWebSocket",
                                            "()Lcom/lattice/LatticeWebSocket;");

    if (ctx->createMethod == NULL) {
        (*env)->DeleteGlobalRef(env, ctx->factory);
        free(ctx);
        return 0;
    }

    lattice_network_factory_t* c_factory = lattice_network_factory_create(
        ctx,
        factory_create_ws_callback,
        factory_destroy_callback
    );

    return (jlong)(intptr_t)c_factory;
}

JNIEXPORT void JNICALL JNI_FN(nativeSetGlobalNetworkFactory)(JNIEnv* env, jobject thiz,
                                                              jlong factoryHandle) {
    lattice_network_factory_t* factory = (lattice_network_factory_t*)(intptr_t)factoryHandle;
    if (factory != NULL) {
        lattice_set_network_factory(factory);
    }
}

JNIEXPORT void JNICALL JNI_FN(nativeTriggerOnOpen)(JNIEnv* env, jobject thiz, jlong clientHandle) {
    lattice_websocket_client_t* client = (lattice_websocket_client_t*)(intptr_t)clientHandle;
    if (client != NULL) {
        lattice_websocket_client_trigger_on_open(client);
    }
}

JNIEXPORT void JNICALL JNI_FN(nativeTriggerOnMessage)(JNIEnv* env, jobject thiz,
                                                       jlong clientHandle, jint type,
                                                       jbyteArray data) {
    __android_log_print(ANDROID_LOG_DEBUG, "LatticeJNI",
        "nativeTriggerOnMessage: clientHandle=%lld, type=%d", (long long)clientHandle, type);

    lattice_websocket_client_t* client = (lattice_websocket_client_t*)(intptr_t)clientHandle;
    if (client == NULL) {
        __android_log_print(ANDROID_LOG_ERROR, "LatticeJNI", "nativeTriggerOnMessage: client is NULL");
        return;
    }

    lattice_websocket_msg_type_t msg_type = (type == 0) ? LATTICE_WS_MSG_TEXT : LATTICE_WS_MSG_BINARY;

    jsize len = data ? (*env)->GetArrayLength(env, data) : 0;
    jbyte* bytes = data ? (*env)->GetByteArrayElements(env, data, NULL) : NULL;

    __android_log_print(ANDROID_LOG_DEBUG, "LatticeJNI",
        "nativeTriggerOnMessage: calling trigger_on_message with len=%d", len);

    lattice_websocket_client_trigger_on_message(client, msg_type, (const uint8_t*)bytes, (size_t)len);

    __android_log_print(ANDROID_LOG_DEBUG, "LatticeJNI", "nativeTriggerOnMessage: trigger_on_message returned");

    if (bytes) (*env)->ReleaseByteArrayElements(env, data, bytes, JNI_ABORT);
}

JNIEXPORT void JNICALL JNI_FN(nativeTriggerOnError)(JNIEnv* env, jobject thiz,
                                                     jlong clientHandle, jstring error) {
    lattice_websocket_client_t* client = (lattice_websocket_client_t*)(intptr_t)clientHandle;
    if (client == NULL) return;

    char* c_error = jstring_to_cstring(env, error);
    lattice_websocket_client_trigger_on_error(client, c_error ? c_error : "Unknown error");
    free(c_error);
}

JNIEXPORT void JNICALL JNI_FN(nativeTriggerOnClose)(JNIEnv* env, jobject thiz,
                                                     jlong clientHandle, jint code, jstring reason) {
    lattice_websocket_client_t* client = (lattice_websocket_client_t*)(intptr_t)clientHandle;
    if (client == NULL) return;

    char* c_reason = jstring_to_cstring(env, reason);
    lattice_websocket_client_trigger_on_close(client, code, c_reason ? c_reason : "");
    free(c_reason);
}
