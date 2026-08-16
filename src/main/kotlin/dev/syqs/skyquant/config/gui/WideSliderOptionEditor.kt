package dev.syqs.skyquant.config.gui

import io.github.notenoughupdates.moulconfig.gui.GuiComponent
import io.github.notenoughupdates.moulconfig.gui.editors.ComponentEditor
import io.github.notenoughupdates.moulconfig.observer.GetSetter
import io.github.notenoughupdates.moulconfig.processor.ProcessedOption

/** Same as MoulConfig's `GuiOptionEditorSlider`, but backed by [WideSliderComponent]. */
class WideSliderOptionEditor(
    option: ProcessedOption,
    minValue: Float,
    maxValue: Float,
    minStep: Float,
) : ComponentEditor(option) {

    // `intoProperty()` is declared as GetSetter<?>, so the cast can't be checked. It is safe
    // because this editor is only built for fields annotated as sliders, which are numeric.
    // MoulConfig's own GuiOptionEditorSlider casts the same way.
    @Suppress("UNCHECKED_CAST")
    private val component: GuiComponent = wrapComponent(
        WideSliderComponent(
            option.intoProperty() as GetSetter<Float>,
            minValue,
            maxValue,
            if (minStep < 0) 0.01f else minStep,
            55,
        ),
    )

    override fun getDelegate(): GuiComponent = component
}
