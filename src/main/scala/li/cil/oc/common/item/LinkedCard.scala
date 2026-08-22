package li.cil.oc.common.item

import li.cil.oc.Settings
import li.cil.oc.util.{ItemUtils, Tooltip}
import net.minecraft.network.chat.Component
import net.minecraft.world.item.{Item, ItemStack, TooltipFlag}
import net.minecraft.world.item.Item.{Properties, TooltipContext}
import net.neoforged.neoforge.common.extensions.IItemExtension

import java.util
import scala.collection.convert.ImplicitConversionsToScala._

class LinkedCard(props: Properties) extends Item(props) with traits.SimpleItem with traits.ItemTier with IItemExtension {
  override def appendHoverText(stack: ItemStack, context: TooltipContext, tooltip: util.List[Component], flag: TooltipFlag): Unit = {
    super.appendHoverText(stack, context, tooltip, flag)
    val tag = ItemUtils.getTag(stack)
    if (tag != null && tag.contains(Settings.namespace + "data")) {
      val data = tag.getCompound(Settings.namespace + "data")
      if (data.contains(Settings.namespace + "tunnel")) {
        val channel = data.getString(Settings.namespace + "tunnel")
        if (channel.length > 13) {
          for (curr <- Tooltip.get(unlocalizedName + "_channel", channel.substring(0, 13) + "...")) {
            tooltip.add(Component.literal(curr).setStyle(Tooltip.DefaultStyle))
          }
        }
        else {
          for (curr <- Tooltip.get(unlocalizedName + "_channel", channel)) {
            tooltip.add(Component.literal(curr).setStyle(Tooltip.DefaultStyle))
          }
        }
      }
    }
  }
}
