package com.example

import com.lattice.LatticeObject

fun main() {
    println("=== Lattice Kotlin Example ===\n")

    // Test 1: Create a Trip (compiler plugin adds LatticeObject interface)
    val trip = Trip()
    println("Created trip")

    // Test 2: Set properties (unmanaged - uses backing field)
    trip.name = "Costa Rica Adventure"
    trip.days = 10
    trip.budget = 3500.0
    trip.isBooked = false

    println("Set properties:")
    println("  name: ${trip.name}")
    println("  days: ${trip.days}")
    println("  budget: ${trip.budget}")
    println("  isBooked: ${trip.isBooked}")

    // Test 3: Check LatticeObject interface members (via cast - IR adds these)
    // Note: IR transformations add members that frontend can't see,
    // so we access through the LatticeObject interface
    val latticeObj = trip as LatticeObject
    println("\nLatticeObject interface:")
    println("  _latticeHandle: ${latticeObj._latticeHandle}")
    println("  _latticeTableName: ${latticeObj._latticeTableName}")
    println("  isManaged: ${latticeObj.isManaged}")

    // Test 4: Modify _latticeHandle (simulating becoming managed)
    println("\nSimulating managed state (handle = 12345):")
    latticeObj._latticeHandle = 12345L
    println("  isManaged: ${latticeObj.isManaged}")
    // Note: actual reads would go to native if LatticeNative was properly linked

    // Reset to unmanaged
    latticeObj._latticeHandle = 0L

    println("\n=== Done ===")
}
