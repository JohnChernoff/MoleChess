package org.chernovia.molechess

data class MoleResult @JvmOverloads constructor(
    @JvmField val success: Boolean = true,
    @JvmField val message: String?,
    @JvmField val user: MoleUser? = null,
    @JvmField val player: MolePlayer? = null
)