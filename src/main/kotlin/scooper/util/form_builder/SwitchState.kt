package scooper.util.form_builder

//source: https://github.com/jkuatdsc/form-builder


import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

class SwitchState(
    name: String,
    initial: Boolean = false,
    transform: Transform<Boolean>? = null,
    validators: List<Validators> = listOf(),
) : BaseState<Boolean>(name = name, initial = initial, transform = transform, validators = validators) {

    override var value: Boolean by mutableStateOf(initial)

    fun update(newValue: Boolean) {
        hideError()
        value = newValue
    }

    override fun validate(): Boolean {
        return true
    }

}