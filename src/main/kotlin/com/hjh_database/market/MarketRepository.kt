package com.hjh_database.market

import com.hjh_database.Hjh_database
import com.hjh_database.warehouse.utils.ItemSerializer
import java.sql.Connection
import java.sql.Statement
import java.util.UUID

/** SQLite 持久层。所有写操作都在单个事务中完成。 */
class MarketRepository(private val plugin: Hjh_database) {

    fun initialize() {
        connection().use { conn ->
            conn.createStatement().use { statement ->
                statement.execute(
                    """
                    CREATE TABLE IF NOT EXISTS market_listings (
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        seller_uuid TEXT NOT NULL,
                        seller_name TEXT NOT NULL,
                        item_data TEXT NOT NULL,
                        price REAL NOT NULL CHECK(price >= 0),
                        listed_at INTEGER NOT NULL,
                        rarity INTEGER
                    )
                    """.trimIndent()
                )
                statement.execute("CREATE INDEX IF NOT EXISTS idx_market_seller ON market_listings(seller_uuid)")
                statement.execute("CREATE INDEX IF NOT EXISTS idx_market_time ON market_listings(listed_at)")
            }
        }
    }

    fun loadAll(): List<MarketListing> {
        val result = mutableListOf<MarketListing>()
        connection().use { conn ->
            conn.prepareStatement(
                "SELECT id, seller_uuid, seller_name, item_data, price, listed_at, rarity FROM market_listings"
            ).use { statement ->
                statement.executeQuery().use { rows ->
                    while (rows.next()) {
                        try {
                            val serialized = rows.getString("item_data")
                            val item = ItemSerializer.base64ToItems(serialized).firstOrNull() ?: continue
                            val rarityValue = rows.getInt("rarity")
                            val rarity = if (rows.wasNull()) null else rarityValue
                            result += MarketListing(
                                id = rows.getLong("id"),
                                sellerUuid = UUID.fromString(rows.getString("seller_uuid")),
                                sellerName = rows.getString("seller_name"),
                                item = item,
                                price = rows.getDouble("price"),
                                listedAt = rows.getLong("listed_at"),
                                rarity = rarity
                            )
                        } catch (ex: Exception) {
                            plugin.logger.severe("跳过无法读取的市场商品 #${rows.getLong("id")}: ${ex.message}")
                        }
                    }
                }
            }
        }
        return result
    }

    fun insert(
        sellerUuid: UUID,
        sellerName: String,
        itemData: String,
        price: Double,
        listedAt: Long,
        rarity: Int?
    ): Long {
        connection().use { conn ->
            conn.prepareStatement(
                """
                INSERT INTO market_listings(seller_uuid, seller_name, item_data, price, listed_at, rarity)
                VALUES (?, ?, ?, ?, ?, ?)
                """.trimIndent(),
                Statement.RETURN_GENERATED_KEYS
            ).use { statement ->
                statement.setString(1, sellerUuid.toString())
                statement.setString(2, sellerName)
                statement.setString(3, itemData)
                statement.setDouble(4, price)
                statement.setLong(5, listedAt)
                if (rarity == null) statement.setNull(6, java.sql.Types.INTEGER) else statement.setInt(6, rarity)
                statement.executeUpdate()
                statement.generatedKeys.use { keys ->
                    if (keys.next()) return keys.getLong(1)
                }
            }
        }
        throw IllegalStateException("市场商品写入成功，但未能取得商品编号")
    }

    /** 删除商品并同时转移双方数据库 money，避免重复购买和只完成一半的交易。 */
    fun completePurchase(
        listingId: Long,
        buyerUuid: UUID,
        sellerUuid: UUID,
        buyerBalanceAfter: Double,
        price: Double
    ): Boolean = transaction { conn ->
        val deleted = conn.prepareStatement("DELETE FROM market_listings WHERE id = ?").use { statement ->
            statement.setLong(1, listingId)
            statement.executeUpdate()
        }
        if (deleted != 1) return@transaction false

        val buyerUpdated = conn.prepareStatement("UPDATE player_data SET money = ? WHERE uuid = ?").use { statement ->
            statement.setDouble(1, buyerBalanceAfter)
            statement.setString(2, buyerUuid.toString())
            statement.executeUpdate()
        }
        if (buyerUpdated != 1) throw IllegalStateException("购买者数据库资料不存在")

        val sellerUpdated = conn.prepareStatement("UPDATE player_data SET money = money + ? WHERE uuid = ?").use { statement ->
            statement.setDouble(1, price)
            statement.setString(2, sellerUuid.toString())
            statement.executeUpdate()
        }
        if (sellerUpdated != 1) throw IllegalStateException("售卖者数据库资料不存在")
        true
    }

    fun delete(listingId: Long): Boolean = transaction { conn ->
        conn.prepareStatement("DELETE FROM market_listings WHERE id = ?").use { statement ->
            statement.setLong(1, listingId)
            statement.executeUpdate() == 1
        }
    }

    private fun connection(): Connection =
        plugin.databaseManager.dataSource?.connection ?: error("数据库连接尚未初始化")

    private fun <T> transaction(block: (Connection) -> T): T {
        connection().use { conn ->
            val originalAutoCommit = conn.autoCommit
            conn.autoCommit = false
            try {
                val result = block(conn)
                conn.commit()
                return result
            } catch (ex: Exception) {
                try {
                    conn.rollback()
                } catch (rollbackEx: Exception) {
                    ex.addSuppressed(rollbackEx)
                }
                throw ex
            } finally {
                conn.autoCommit = originalAutoCommit
            }
        }
    }
}
