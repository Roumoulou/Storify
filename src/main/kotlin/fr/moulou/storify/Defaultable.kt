package fr.moulou.storify

fun interface Defaultable<DATA> {
    fun getDefault(): DATA
}