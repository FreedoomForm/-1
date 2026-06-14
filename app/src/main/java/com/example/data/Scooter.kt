package com.example.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "scooters")
data class Scooter(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val name: String,
    /** Документированный номер (из техпаспорта / гос. регистрации). */
    val documentedNumber: String? = null
)
