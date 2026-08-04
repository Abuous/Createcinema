package com.yfy.createcinema;

import com.yfy.createcinema.block.BurnerBlock;
import com.yfy.createcinema.block.CableBlock;
import com.yfy.createcinema.block.NetworkProjectorBlock;
import com.yfy.createcinema.block.ProjectorBlock;
import com.yfy.createcinema.block.ScreenBlock;
import com.yfy.createcinema.block.SpeakerBlock;
import com.yfy.createcinema.block.WrenchableBlock;
import com.yfy.createcinema.blockentity.BurnerBlockEntity;
import com.yfy.createcinema.blockentity.NetworkProjectorBlockEntity;
import com.yfy.createcinema.blockentity.ProjectorBlockEntity;
import com.yfy.createcinema.gui.BurnerMenu;
import com.yfy.createcinema.gui.NetworkProjectorMenu;
import com.yfy.createcinema.gui.ProjectorMenu;
import com.yfy.createcinema.gui.RemoteControlUpgradeMenu;
import com.yfy.createcinema.item.FilmItem;
import com.yfy.createcinema.item.ContinuousPlayUpgradeItem;
import com.yfy.createcinema.item.ConfigManagerItem;
import com.yfy.createcinema.item.RemoteControlUpgradeItem;
import com.yfy.createcinema.item.TooltipBlockItem;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemContainerContents;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.neoforged.neoforge.common.extensions.IMenuTypeExtension;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

import java.util.Set;
import java.util.function.Supplier;

public class ModRegistry {
    public static final DeferredRegister.Blocks BLOCKS = DeferredRegister.createBlocks(CreateCinema.MODID);
    public static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(CreateCinema.MODID);
    public static final DeferredRegister<BlockEntityType<?>> BLOCK_ENTITIES = DeferredRegister.create(Registries.BLOCK_ENTITY_TYPE, CreateCinema.MODID);
    public static final DeferredRegister<MenuType<?>> MENU_TYPES = DeferredRegister.create(Registries.MENU, CreateCinema.MODID);
    public static final DeferredRegister<CreativeModeTab> CREATIVE_TABS = DeferredRegister.create(Registries.CREATIVE_MODE_TAB, CreateCinema.MODID);
    public static final DeferredRegister.DataComponents DATA_COMPONENTS =
            DeferredRegister.createDataComponents(Registries.DATA_COMPONENT_TYPE, CreateCinema.MODID);

    public static final DeferredHolder<DataComponentType<?>, DataComponentType<ItemContainerContents>> REMOTE_FREQUENCIES =
            DATA_COMPONENTS.registerComponentType("remote_frequencies", builder -> builder
                    .persistent(ItemContainerContents.CODEC)
                    .networkSynchronized(ItemContainerContents.STREAM_CODEC));

    public static final DeferredItem<FilmItem> FILM = ITEMS.register("film", () -> new FilmItem(new Item.Properties().stacksTo(1)));
    public static final DeferredItem<ContinuousPlayUpgradeItem> CONTINUOUS_PLAY_UPGRADE = ITEMS.register("continuous_play_upgrade",
            () -> new ContinuousPlayUpgradeItem(new Item.Properties().stacksTo(1)));
    public static final DeferredItem<RemoteControlUpgradeItem> REMOTE_CONTROL_UPGRADE = ITEMS.register("remote_control_upgrade",
            () -> new RemoteControlUpgradeItem(new Item.Properties().stacksTo(1)));
    public static final DeferredItem<ConfigManagerItem> CONFIG_MANAGER = ITEMS.register("config_manager",
            () -> new ConfigManagerItem(new Item.Properties().stacksTo(1)));

    public static final DeferredBlock<BurnerBlock> BURNER = BLOCKS.register("burner",
            () -> new BurnerBlock(BlockBehaviour.Properties.of().strength(3.5f).requiresCorrectToolForDrops()));
    public static final DeferredBlock<ScreenBlock> SCREEN = BLOCKS.register("screen",
            () -> new ScreenBlock(BlockBehaviour.Properties.of().strength(1.0f).noOcclusion()));
    public static final DeferredBlock<ScreenBlock> BLACK_SCREEN = BLOCKS.register("black_screen",
            () -> new ScreenBlock(BlockBehaviour.Properties.of().strength(1.0f).noOcclusion()));
    public static final DeferredBlock<ProjectorBlock> PROJECTOR = BLOCKS.register("projector",
            () -> new ProjectorBlock(BlockBehaviour.Properties.of().strength(3.5f).requiresCorrectToolForDrops().noOcclusion()));
    public static final DeferredBlock<SpeakerBlock> SPEAKER = BLOCKS.register("speaker",
            () -> new SpeakerBlock(BlockBehaviour.Properties.of().strength(2.0f).requiresCorrectToolForDrops()));
    public static final DeferredBlock<CableBlock> CABLE = BLOCKS.register("cable",
            () -> new CableBlock(BlockBehaviour.Properties.of().strength(0.6f).noOcclusion()));
    public static final DeferredBlock<NetworkProjectorBlock> NETWORK_PROJECTOR = BLOCKS.register("network_projector",
            () -> new NetworkProjectorBlock(BlockBehaviour.Properties.of().strength(3.5f).requiresCorrectToolForDrops().noOcclusion()));
    public static final DeferredBlock<WrenchableBlock> DARKROOM_BLOCK = BLOCKS.register("darkroom_block",
            () -> new WrenchableBlock(BlockBehaviour.Properties.of().strength(2.5f).requiresCorrectToolForDrops()));

    public static final DeferredItem<BlockItem> BURNER_ITEM = ITEMS.registerSimpleBlockItem(BURNER);
    public static final DeferredItem<BlockItem> SCREEN_ITEM = ITEMS.register("screen",
            () -> new TooltipBlockItem(SCREEN.get(), new Item.Properties(), "tooltip.createcinema.screen", 2));
    public static final DeferredItem<BlockItem> BLACK_SCREEN_ITEM = ITEMS.register("black_screen",
            () -> new TooltipBlockItem(BLACK_SCREEN.get(), new Item.Properties(), "tooltip.createcinema.black_screen", 2));
    public static final DeferredItem<BlockItem> PROJECTOR_ITEM = ITEMS.registerSimpleBlockItem(PROJECTOR);
    public static final DeferredItem<BlockItem> SPEAKER_ITEM = ITEMS.register("speaker",
            () -> new TooltipBlockItem(SPEAKER.get(), new Item.Properties(), "tooltip.createcinema.speaker", 2));
    public static final DeferredItem<BlockItem> CABLE_ITEM = ITEMS.register("cable",
            () -> new TooltipBlockItem(CABLE.get(), new Item.Properties(), "tooltip.createcinema.cable", 2));
    public static final DeferredItem<BlockItem> NETWORK_PROJECTOR_ITEM = ITEMS.registerSimpleBlockItem(NETWORK_PROJECTOR);
    public static final DeferredItem<BlockItem> DARKROOM_BLOCK_ITEM = ITEMS.register("darkroom_block",
            () -> new TooltipBlockItem(DARKROOM_BLOCK.get(), new Item.Properties(), "tooltip.createcinema.darkroom_block", 2));

    public static final Supplier<BlockEntityType<BurnerBlockEntity>> BURNER_BE = BLOCK_ENTITIES.register("burner",
            () -> new BlockEntityType<>(BurnerBlockEntity::new, Set.of(BURNER.get()), null));
    public static final Supplier<BlockEntityType<ProjectorBlockEntity>> PROJECTOR_BE = BLOCK_ENTITIES.register("projector",
            () -> new BlockEntityType<>(ProjectorBlockEntity::new, Set.of(PROJECTOR.get()), null));
    public static final Supplier<BlockEntityType<NetworkProjectorBlockEntity>> NETWORK_PROJECTOR_BE = BLOCK_ENTITIES.register("network_projector",
            () -> new BlockEntityType<>(NetworkProjectorBlockEntity::new, Set.of(NETWORK_PROJECTOR.get()), null));

    public static final Supplier<MenuType<BurnerMenu>> BURNER_MENU = MENU_TYPES.register("burner",
            () -> IMenuTypeExtension.create((windowId, inv, data) -> new BurnerMenu(windowId, inv, data.readBlockPos())));
    public static final Supplier<MenuType<ProjectorMenu>> PROJECTOR_MENU = MENU_TYPES.register("projector",
            () -> IMenuTypeExtension.create((windowId, inv, data) -> new ProjectorMenu(windowId, inv, data.readBlockPos())));
    public static final Supplier<MenuType<NetworkProjectorMenu>> NETWORK_PROJECTOR_MENU = MENU_TYPES.register("network_projector",
            () -> IMenuTypeExtension.create((windowId, inv, data) -> new NetworkProjectorMenu(windowId, inv, data.readBlockPos())));
    public static final Supplier<MenuType<RemoteControlUpgradeMenu>> REMOTE_CONTROL_UPGRADE_MENU = MENU_TYPES.register("remote_control_upgrade",
            () -> IMenuTypeExtension.create((windowId, inv, data) -> new RemoteControlUpgradeMenu(windowId, inv, data)));

    public static final Supplier<CreativeModeTab> CREATIVE_TAB = CREATIVE_TABS.register("createcinema",
            () -> CreativeModeTab.builder()
                    .title(Component.translatable("itemGroup.createcinema"))
                    .icon(() -> new ItemStack(PROJECTOR_ITEM.get()))
                    .displayItems((params, output) -> {
                        output.accept(BURNER_ITEM.get());
                        output.accept(SCREEN_ITEM.get());
                        output.accept(BLACK_SCREEN_ITEM.get());
                        output.accept(PROJECTOR_ITEM.get());
                        output.accept(SPEAKER_ITEM.get());
                        output.accept(CABLE_ITEM.get());
                        output.accept(NETWORK_PROJECTOR_ITEM.get());
                        output.accept(DARKROOM_BLOCK_ITEM.get());
                        output.accept(FILM.get());
                        output.accept(CONTINUOUS_PLAY_UPGRADE.get());
                        output.accept(REMOTE_CONTROL_UPGRADE.get());
                        output.accept(CONFIG_MANAGER.get());
                    })
                    .build());
}
