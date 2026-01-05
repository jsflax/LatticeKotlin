package com.example.notes

import com.lattice.Model
import kotlinx.datetime.Instant

/**
 * Note model - matches the Note model in LatticePython and LatticeJS examples.
 */
@Model
class Note {
    var text: String = ""
    var createdAt: Instant = Instant.fromEpochMilliseconds(0)
}
