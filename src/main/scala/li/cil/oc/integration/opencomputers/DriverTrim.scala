package li.cil.oc.integration.opencomputers

import li.cil.oc.api
import li.cil.oc.api.network.{EnvironmentHost, ManagedEnvironment}
import li.cil.oc.common.Slot
import li.cil.oc.server.component
import net.minecraft.tags.ItemTags
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.armortrim.TrimPatterns

import scala.jdk.OptionConverters.RichOptional

object DriverTrim extends Item{
  override def worksWith(stack: ItemStack): Boolean = return stack.is(ItemTags.TRIM_TEMPLATES)

  override def createEnvironment(stack: ItemStack, host: EnvironmentHost): ManagedEnvironment = {
    RichOptional(TrimPatterns.getFromTemplate(host.getEnvironmentLevel.registryAccess(), stack)).toScala match {
      case Some(pattern: _root_.net.minecraft.core.Holder.Reference[_root_.net.minecraft.world.item.armortrim.TrimPattern]) => return new component.Trim(pattern.value())
      case scala.None => return null
    }
  }

  override def slot(stack: ItemStack): String = return Slot.Trim
}
