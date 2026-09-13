package fr.moulou.storify

interface StoreFormat<T> {
    fun fileExtension(): String
}