package com.lattice

import kotlin.test.*

/**
 * Tests for @LatticeEnum property support (TEXT and INTEGER storage).
 */
class EnumTests {

    @AfterTest
    fun tearDownTestLattices() {
        cleanupTestLattices()
    }

    @BeforeTest
    fun setup() {
        registerTestModelFactories()
    }

    /**
     * Tests @LatticeEnum property support (stored as TEXT by default).
     */
    @Test
    fun test_LatticeEnumAsText() {
        val lattice = testLattice(Task::class)

        val task = Task()
        task.title = "Important Task"
        task.priority = Priority.HIGH

        lattice.add(task)

        // Retrieve and verify
        val retrieved = lattice.objects(Task::class).first()
        assertNotNull(retrieved)
        assertEquals("Important Task", retrieved.title)
        assertEquals(Priority.HIGH, retrieved.priority)

        lattice.close()
    }

    /**
     * Tests @LatticeEnum with storeAsOrdinal=true (stored as INTEGER).
     */
    @Test
    fun test_LatticeEnumAsOrdinal() {
        val lattice = testLattice(Task::class)

        val task = Task()
        task.title = "Status Task"
        task.status = Status.IN_PROGRESS

        lattice.add(task)

        // Retrieve and verify
        val retrieved = lattice.objects(Task::class).first()
        assertNotNull(retrieved)
        assertEquals("Status Task", retrieved.title)
        assertEquals(Status.IN_PROGRESS, retrieved.status)

        lattice.close()
    }

    /**
     * Tests nullable @LatticeEnum properties.
     */
    @Test
    fun test_NullableEnum() {
        val lattice = testLattice(Task::class)

        // Create task without optional enums
        val task1 = Task()
        task1.title = "No Priority Opt"
        task1.priority = Priority.LOW
        task1.status = Status.PENDING
        // priorityOpt and statusOpt are null

        // Create task with optional enums
        val task2 = Task()
        task2.title = "With Priority Opt"
        task2.priority = Priority.MEDIUM
        task2.priorityOpt = Priority.CRITICAL
        task2.status = Status.COMPLETED
        task2.statusOpt = Status.CANCELLED

        lattice.add(task1)
        lattice.add(task2)

        // Retrieve and verify
        val tasks = lattice.objects(Task::class).orderBy("title")

        val noOpt = tasks.firstOrNull { it.title == "No Priority Opt" }
        assertNotNull(noOpt)
        assertNull(noOpt!!.priorityOpt)
        assertNull(noOpt.statusOpt)
        assertEquals(Priority.LOW, noOpt.priority)
        assertEquals(Status.PENDING, noOpt.status)

        val withOpt = tasks.firstOrNull { it.title == "With Priority Opt" }
        assertNotNull(withOpt)
        assertNotNull(withOpt!!.priorityOpt)
        assertNotNull(withOpt.statusOpt)
        assertEquals(Priority.CRITICAL, withOpt.priorityOpt)
        assertEquals(Status.CANCELLED, withOpt.statusOpt)

        lattice.close()
    }

    /**
     * Tests updating @LatticeEnum values.
     */
    @Test
    fun test_UpdateEnum() {
        val lattice = testLattice(Task::class)

        val task = Task()
        task.title = "Updatable"
        task.priority = Priority.LOW
        task.status = Status.PENDING

        lattice.add(task)

        // Update both enum values
        task.priority = Priority.CRITICAL
        task.status = Status.COMPLETED

        // Retrieve and verify update persisted
        val retrieved = lattice.objects(Task::class).first()
        assertNotNull(retrieved)
        assertEquals(Priority.CRITICAL, retrieved.priority)
        assertEquals(Status.COMPLETED, retrieved.status)

        lattice.close()
    }

    /**
     * Tests all enum values for Priority (TEXT storage).
     */
    @Test
    fun test_AllPriorityValues() {
        val lattice = testLattice(Task::class)

        Priority.entries.forEachIndexed { index, priority ->
            val task = Task()
            task.title = "Priority $index"
            task.priority = priority
            task.status = Status.PENDING
            lattice.add(task)
        }

        // Retrieve and verify all values
        val tasks = lattice.objects(Task::class).orderBy("title")
        assertEquals(Priority.entries.size, tasks.count)

        Priority.entries.forEachIndexed { index, priority ->
            val task = tasks.first { it.title == "Priority $index" }
            assertEquals(priority, task.priority)
        }

        lattice.close()
    }

    /**
     * Tests all enum values for Status (INTEGER storage).
     */
    @Test
    fun test_AllStatusValues() {
        val lattice = testLattice(Task::class)

        Status.entries.forEachIndexed { index, status ->
            val task = Task()
            task.title = "Status $index"
            task.priority = Priority.LOW
            task.status = status
            lattice.add(task)
        }

        // Retrieve and verify all values
        val tasks = lattice.objects(Task::class).orderBy("title")
        assertEquals(Status.entries.size, tasks.count)

        Status.entries.forEachIndexed { index, status ->
            val task = tasks.first { it.title == "Status $index" }
            assertEquals(status, task.status)
        }

        lattice.close()
    }
}
