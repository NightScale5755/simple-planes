package xyz.przemyk.simpleplanes.upgrades.tnt;

import com.mojang.blaze3d.matrix.MatrixStack;
import net.minecraft.block.Blocks;
import net.minecraft.client.renderer.IRenderTypeBuffer;
import net.minecraft.entity.item.TNTEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.network.PacketBuffer;
import net.minecraft.util.ResourceLocation;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import xyz.przemyk.simpleplanes.SimplePlanesMod;
import xyz.przemyk.simpleplanes.entities.PlaneEntity;
import xyz.przemyk.simpleplanes.client.render.models.BackSeatBlockModel;
import xyz.przemyk.simpleplanes.setup.SimplePlanesUpgrades;
import xyz.przemyk.simpleplanes.upgrades.Upgrade;

public class TNTUpgrade extends Upgrade {
    public static final ResourceLocation TEXTURE = new ResourceLocation(SimplePlanesMod.MODID, "textures/plane_upgrades/tnt.png");

    public TNTUpgrade(PlaneEntity planeEntity) {
        super(SimplePlanesUpgrades.TNT.get(), planeEntity);
    }

    @Override
    public void onItemRightClick(PlayerInteractEvent.RightClickItem event) {
        ItemStack itemStack = event.getPlayer().getHeldItem(event.getHand());
        if (itemStack.getItem() == Items.FLINT_AND_STEEL) {
            TNTEntity tntEntity = new TNTEntity(planeEntity.world, planeEntity.getPosX() - 1.0, planeEntity.getPosY(), planeEntity.getPosZ(),
                event.getPlayer());
            tntEntity.setMotion(planeEntity.getMotion());
            planeEntity.world.addEntity(tntEntity);
            itemStack.damageItem(1, event.getPlayer(), playerEntity -> playerEntity.sendBreakAnimation(event.getHand()));
            remove();
        }
    }

    @Override
    public void render(MatrixStack matrixStack, IRenderTypeBuffer buffer, int packedLight, float partialTicks) {
        BackSeatBlockModel.renderBlock(planeEntity, partialTicks, matrixStack, buffer, packedLight, Blocks.TNT.getDefaultState());
    }

    @Override
    public void writePacket(PacketBuffer buffer) {}

    @Override
    public void readPacket(PacketBuffer buffer) {}

    @Override
    public void dropItems() {
        planeEntity.entityDropItem(Items.TNT);
    }
}
