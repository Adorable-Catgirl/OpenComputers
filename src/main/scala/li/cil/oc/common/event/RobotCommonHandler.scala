package li.cil.oc.common.event

import li.cil.oc.api.event.RobotUsedToolEvent
import li.cil.oc.api.internal
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent

object RobotCommonHandler {
  @SubscribeEvent
  def onRobotApplyDamageRate(e: RobotUsedToolEvent.ApplyDamageRate) {
    e.agent match {
      case robot: internal.Robot =>
        if (e.toolAfterUse.isItemStackDamageable) {
          val damage = e.toolAfterUse.getItemDamage - e.toolBeforeUse.getItemDamage
          if (damage > 0) {
            val actualDamage = damage * e.getDamageRate
            val repairedDamage = if (robot.player.getRNG.nextDouble() > 0.5) damage - math.floor(actualDamage).toInt else damage - math.ceil(actualDamage).toInt
            e.toolAfterUse.setItemDamage(e.toolAfterUse.getItemDamage - repairedDamage)
          }
        }
      case _ =>
    }
  }
}
