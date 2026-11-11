package com.example.mana_bike_v001

data class TransactionItem(
    val id: Int,
    val amount: Double,
    val timestamp: Long,
    val formattedDate: String,
    val bank: String? = null,
    val channel: String? = null
)
