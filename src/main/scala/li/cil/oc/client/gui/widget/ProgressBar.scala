package li.cil.oc.client.gui.widget

import li.cil.oc.client.Textures
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.client.gui.components.AbstractWidget
import net.minecraft.client.gui.narration.NarrationElementOutput
import net.minecraft.network.chat.CommonComponents
import net.minecraft.resources.ResourceLocation

class ProgressBar(x: Int, y: Int, width: Int = 140, height: Int = 12,
                  val texture: ResourceLocation = Textures.GUI.Bar)
  extends AbstractWidget(x, y, width, height, CommonComponents.EMPTY) {
  var level = 0.0

  override def renderWidget(graphics: GuiGraphics, mouseX: Int, mouseY: Int, partialTick: Float): Unit = {
    if (level > 0) {
      val w = (width.toFloat * level).toInt
      graphics.blit(texture, x, y, 0, 0, w, height, width, height)
    }
  }

  override def updateWidgetNarration(narrationElementOutput: NarrationElementOutput): Unit = {}
}
