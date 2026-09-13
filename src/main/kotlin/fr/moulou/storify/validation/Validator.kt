package fr.moulou.storify.validation

interface Validator<T : Any> {
    fun validate(data: T, ctx: ValidationContext)
}