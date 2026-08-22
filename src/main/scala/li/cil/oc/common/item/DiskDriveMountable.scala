package li.cil.oc.common.item

import li.cil.oc.common.container.DiskDriveMountableInventory
import li.cil.oc.common.menu.MenuTypes
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.{InteractionHand, InteractionResultHolder}
import net.minecraft.world.entity.player.Player
import net.minecraft.world.item.{Item, ItemStack}
import net.minecraft.world.item.Item.Properties
import net.minecraft.world.level.Level
import net.neoforged.neoforge.common.extensions.IItemExtension

class DiskDriveMountable(props: Properties) extends Item(props) with traits.SimpleItem with IItemExtension {
  override def use(level: Level, player: Player, hand: InteractionHand) = {
    val stack = player.getItemInHand(hand)
    if (!level.isClientSide) player match {
      case srvPlr: ServerPlayer => MenuTypes.openDiskDriveGui(srvPlr, new DiskDriveMountableInventory {
        override def container: ItemStack = stack

        override def stillValid(player: Player) = player == srvPlr
      })
      case _ =>
    }
    InteractionResultHolder.sidedSuccess(stack, level.isClientSide)
  }
}
