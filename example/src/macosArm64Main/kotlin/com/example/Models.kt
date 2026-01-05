package com.example

import com.lattice.Model
import com.lattice.Link
import com.lattice.LinkList

@Model
class Trip {
    var name: String = ""
    var days: Int = 0
    var budget: Double = 0.0
    var isBooked: Boolean = false

    // Relationships (future)
    // @Link var destination: Destination? = null
    // @LinkList var travelers: List<Traveler> = emptyList()
}

@Model
class Destination {
    var city: String = ""
    var country: String = ""
    var latitude: Double = 0.0
    var longitude: Double = 0.0
}

@Model
class Traveler {
    var firstName: String = ""
    var lastName: String = ""
    var email: String? = null
}
