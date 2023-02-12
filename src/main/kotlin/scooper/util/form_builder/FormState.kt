@file:Suppress("unused")

package scooper.util.form_builder

//source: https://github.com/jkuatdsc/form-builder

import kotlinx.coroutines.flow.*
import kotlin.reflect.KClass
import kotlin.reflect.KParameter
import kotlin.reflect.full.declaredMemberProperties

/**
 * This class represents the state of the whole form, i.e, the whole collection of fields. It is used to manage all of the states in terms of accessing data and validations.
 * @param fields this is a list of all fields in the form. We pass them as a parameter to the constructor for ease of management and access.
 *
 * @author [Linus Muema](https://github.com/linusmuema)
 * @created 05/04/2022 - 10:00 AM
 */
open class FormState<T : BaseState<*>>(val fields: List<T>) {

    private var snapshot: Map<String, Any>? = null

    /**
     * This function is used to validate the whole form. It goes through all fields calling the [BaseState.validate] function. If all of them return true, then the function also returns true.
     */
    fun validate(): Boolean = fields.map { it.validate() }.all { it }

    fun hideErrors() = fields.forEach { it.hideError() }

    /**
     * This function gets a single field state. It uses the name specified in the [BaseState.name] field to find the field.
     * @param name the name of the field to get.
     */
    inline fun <reified u> getState(name: String): u = fields.first { it.name == name } as u

    /**
     * This function is used to access the data in the whole form. It goes through all fields calling the [BaseState.getData] function and stores them in a [Map] of [String] to [Any]. This map is then used in a constructor to create the specified class.
     * @param dataClass the class to create using the data in the form data.
     */
    fun <T : Any> getData(dataClass: KClass<T>): T {
        val map = fields.associate { it.name to it.getData() }
        val constructor = dataClass.constructors.last()
        val args: Map<KParameter, Any?> = constructor.parameters.associateWith { kParameter ->
            @Suppress("UNCHECKED_CAST")
            val value = if ((kParameter.type.classifier as KClass<Any>).java.isEnum) {
                (kParameter.type.classifier as KClass<Any>).java.enumConstants.filter {
                    (it as Enum<*>).name == map[kParameter.name]
                }.map { it as Enum<*> }.firstOrNull()
            } else {
                map[kParameter.name]
            }
            value
        }
        return constructor.callBy(args)
    }

    fun <D : Any> setData(data: D) {
        @Suppress("UNCHECKED_CAST")
        val map = fields.associate { it.name to it as BaseState<Any> }

        data::class.declaredMemberProperties.forEach {
            val value: Any? = it.getter.call(data)
            if (value != null) {
                if (value.javaClass.isEnum) {
                    map[it.name]?.value = (value as Enum<*>).name
                } else {
                    map[it.name]?.value = value
                }
            }
        }
    }

    fun takeSnapshot() {
        this.snapshot = this.fields.associate { it.name to it.value as Any }
    }

    fun restoreSnapshot() {
        if (snapshot == null) {
            throw IllegalStateException("There is no snapshot can be restored!")
        }
        snapshot?.forEach {
            getState<BaseState<Any>>(name = it.key).value = it.value
        }
    }
}