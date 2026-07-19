package com.hjh_database.qixiazhen.busuan.data

import java.time.LocalDate
import java.util.UUID

data class BusuanRecord(
    val playerId: UUID,
    val playerName: String,
    val requestDate: LocalDate,
    val fortuneId: String?
)

