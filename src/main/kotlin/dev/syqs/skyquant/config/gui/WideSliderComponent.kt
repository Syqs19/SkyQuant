package dev.syqs.skyquant.config.gui

import io.github.notenoughupdates.moulconfig.common.IMinecraft
import io.github.notenoughupdates.moulconfig.gui.GuiImmediateContext
import io.github.notenoughupdates.moulconfig.gui.KeyboardEvent
import io.github.notenoughupdates.moulconfig.gui.MouseEvent
import io.github.notenoughupdates.moulconfig.gui.component.SliderComponent
import io.github.notenoughupdates.moulconfig.gui.component.TextFieldComponent
import io.github.notenoughupdates.moulconfig.observer.GetSetter
import java.util.function.BiFunction
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.max

/**
 * Copy of MoulConfig's `SliderWithTextComponent` with a wider number field (the library
 * hardcodes 20px, which is only wide enough for whole numbers and cuts off decimals like
 * "1.4" down to "1."). `componentNumberInput` is private in the original with no public way
 * to resize it, so the whole class had to be duplicated to change [numberFieldWidth].
 */
private const val NUMBER_FIELD_WIDTH = 40

/**
 * Decimal count is derived from [minStep] (e.g. step 0.05 -> 2 decimals) instead of using
 * Float.toString() directly, since that prints full binary-float precision noise for values
 * accumulated via repeated rounding (e.g. "1.3000000476837158" instead of "1.3").
 */
private fun formatValue(value: Float, minStep: Float): String {
    val decimals = max(0, -floor(log10(minStep.toDouble())).toInt())
    return "%.${decimals}f".format(value)
}

open class WideSliderComponent(
    value: GetSetter<Float>,
    minValue: Float,
    maxValue: Float,
    minStep: Float,
    width: Int,
) : SliderComponent(value, minValue, maxValue, minStep, width) {

    override fun render(context: GuiImmediateContext) {
        context.renderContext.translate(-(width / 3).toFloat(), 0f)
        super.render(context)
        context.renderContext.translate(60f, -5f)
        componentNumberInput.render(context.translated(60, -5, componentNumberInput.width, 18))
    }

    private fun GuiImmediateContext.isHovered(): Boolean {
        return mouseX in 0 - width / 3 until width - 13 && mouseY in 0 until height
    }

    override fun mouseEvent(mouseEvent: MouseEvent, context: GuiImmediateContext): Boolean {
        if (!context.renderContext.isMouseButtonDown(0)) clicked = false
        if (context.isHovered() && mouseEvent is MouseEvent.Click && mouseEvent.mouseState && mouseEvent.mouseButton == 0) {
            clicked = true
        }
        if (clicked) {
            setValueFromContext(context)
            return true
        }
        return componentNumberInput.mouseEvent(mouseEvent, context.translated(45, -5, componentNumberInput.width, 18))
    }

    override fun keyboardEvent(event: KeyboardEvent, context: GuiImmediateContext): Boolean {
        componentNumberInput.setShouldExpandToFit(true)
        return componentNumberInput.keyboardEvent(event, context)
    }

    override fun setValueFromContext(context: GuiImmediateContext) {
        var v: Float = (context.mouseX + width / 3) * (maxValue - minValue) / context.width + minValue
        v = v.coerceIn(minValue, maxValue)
        v = Math.round(v / minStep) * minStep
        value.set(v)
    }

    private val componentNumberInput by lazy {
        TextFieldComponent(
            object : GetSetter<String> {
                var editingBuffer: String = ""

                override fun get(): String {
                    if (isInFocus) return editingBuffer
                    val num = try {
                        value.get()
                    } catch (e: NumberFormatException) {
                        0f
                    }
                    val stringNum = formatValue(num, minStep)
                    return stringNum.also { editingBuffer = it }
                }

                override fun set(newValue: String) {
                    editingBuffer = newValue
                    val num = try {
                        editingBuffer.toFloat()
                    } catch (e: NumberFormatException) {
                        0f
                    }
                    value.set(num).also { editingBuffer = formatValue(num, minStep) }
                }
            },
            NUMBER_FIELD_WIDTH,
            GetSetter.constant(true),
            "",
            IMinecraft.INSTANCE.defaultFontRenderer,
        )
    }

    override fun <T : Any?> foldChildren(initial: T, visitor: BiFunction<io.github.notenoughupdates.moulconfig.gui.GuiComponent, T, T>): T {
        return visitor.apply(componentNumberInput, initial)
    }
}
