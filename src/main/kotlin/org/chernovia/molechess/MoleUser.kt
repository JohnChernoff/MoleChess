package org.chernovia.molechess

import com.fasterxml.jackson.databind.JsonNode
import org.chernovia.lib.zugserv.Connection
import java.util.*

class MoleUser @JvmOverloads constructor(
    @JvmField val conn: Connection?,
    @JvmField val oauth: String?,
    @JvmField val name: String,
    private val data: MoleData? = null
) {

    init {
        if (conn != null) {
            conn.status = Connection.Status.STATUS_OK
        }
    }

    inner class MoleData(
        @JvmField val wins: Int,
        @JvmField val losses: Int,
        @JvmField val rating: Int,
        @JvmField val about: String
    ) {
        override fun toString(): String {
            return "Wins: $wins, Losses: $losses, Rating: $rating"
        }

        fun toJSON(ratingOnly: Boolean): JsonNode {
            val node = MoleServ.OBJ_MAPPER.createObjectNode()
            if (!ratingOnly) {
                node.put("wins", wins)
                node.put("losses", losses)
                node.put("about", about)
            }
            node.put("rating", rating)
            return node
        }
    }

    fun withData(wins: Int, losses: Int, rating: Int, about: String) =
        MoleUser(conn, oauth, name, MoleData(wins, losses, rating, about))

    fun withConnection(conn: Connection) = MoleUser(conn, oauth, name, data)

    val emptyData: MoleData
        get() = this.MoleData(0, 0, 0, "")

    fun getData(): Optional<MoleData> {
        return Optional.ofNullable(data)
    }

    fun sameConnection(c: Connection) = conn === c

    fun isActiveUser() = if (conn == null || conn.status == null) false else conn.status == Connection.Status.STATUS_OK

    fun tell(msg: String? = "serv_msg", game: MoleGame?) {
        tell("serv_msg", msg, game)
    }

    @JvmOverloads
    fun tell(type: String? = "serv_msg", msg: String?, game: MoleGame? = null) {
        val node = MoleServ.OBJ_MAPPER.createObjectNode()
        node.put("msg", msg)
        node.put("source", game?.title ?: "serv")
        tell(type, node)
    }

    fun tell(type: String?, node: JsonNode?) {
        conn?.tell(type, node)
    }

    fun toJSON(ratingOnly: Boolean): JsonNode {
        val obj = MoleServ.OBJ_MAPPER.createObjectNode()
        obj.put("name", name)
        if (data != null) obj.set<JsonNode>("data", data.toJSON(ratingOnly))
        return obj
    }

    override fun equals(other: Any?): Boolean {
        if (other === this) return true
        return if (other !is MoleUser) false else other.oauth == oauth
    }

    override fun hashCode(): Int {
        var result = conn?.hashCode() ?: 0
        result = 31 * result + (oauth?.hashCode() ?: 0)
        result = 31 * result + (name?.hashCode() ?: 0)
        result = 31 * result + (data?.hashCode() ?: 0)
        return result
    }
}