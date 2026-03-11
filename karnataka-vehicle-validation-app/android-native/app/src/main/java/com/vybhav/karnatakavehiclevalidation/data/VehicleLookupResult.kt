package com.vybhav.karnatakavehiclevalidation.data

data class VehicleLookupResult(
    val normalizedRegistration: String,
    val matchedFuelType: FuelType?,
    val records: List<PucRecord>,
    val checkedFuelTypes: List<FuelType>,
) {
    val hasResults: Boolean
        get() = records.isNotEmpty()
}
