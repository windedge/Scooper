package scooper.util.form_builder

//source: https://github.com/jkuatdsc/form-builder

fun String.isNumeric(): Boolean {
    return this.toIntOrNull()?.let { true } ?: false
}