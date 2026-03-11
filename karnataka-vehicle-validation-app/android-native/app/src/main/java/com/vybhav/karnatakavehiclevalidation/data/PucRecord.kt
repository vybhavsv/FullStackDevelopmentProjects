package com.vybhav.karnatakavehiclevalidation.data

data class PucRecord(
    val puccNumber: String,
    val registrationNumber: String,
    val make: String,
    val category: String,
    val registrationDate: String,
    val testDate: String,
    val testTime: String,
    val validDate: String,
    val result: String,
    val cancelled: String,
    val detailsUrl: String,
    val hsuMean: String,
    val kMean: String,
    val oilTempMean: String,
    val rpmMaxMean: String,
    val rpmMinMean: String,
)
