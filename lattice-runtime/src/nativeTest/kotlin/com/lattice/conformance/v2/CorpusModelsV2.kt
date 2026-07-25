package com.lattice.conformance.v2

import com.lattice.Model

/*
 * Version-2 catalog shapes for the corpus migration scenarios.
 *
 * The compiler plugin derives the table name from the class SIMPLE name, so a
 * separate package lets these coexist with the v1 shapes in
 * com.lattice.conformance while mapping to the same tables ("CfWidget",
 * "CfBlobDoc"). The Lattice factory registry is keyed by exact KClass, so both
 * versions can be registered simultaneously.
 */

@Model
class CfWidget {
    var label: String = ""
    var count: Long = 0
    var note: String? = null
}

@Model
class CfBlobDoc {
    var label: String = ""
    var payload: ByteArray = ByteArray(0)
    var stars: Long = 0
}
