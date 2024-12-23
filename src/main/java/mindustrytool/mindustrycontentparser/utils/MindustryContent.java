package mindustrytool.mindustrycontentparser.utils;

import arc.Core;
import arc.files.Fi;
import arc.graphics.*;
import arc.graphics.g2d.*;
import arc.graphics.g2d.TextureAtlas.AtlasRegion;
import arc.graphics.g2d.TextureAtlas.TextureAtlasData;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import mindustry.*;
import mindustry.core.*;
import mindustry.mod.Mods;
import mindustry.world.*;
import mindustrytool.mindustrycontentparser.EnvConfig;

import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class MindustryContent {

    public final String schemHeader = Vars.schematicBaseStart;

    private final EnvConfig config;
    private final DrawBatch drawBatch;

    @PostConstruct
    public void init() {

        Vars.content = new ContentLoader();
        Vars.content.createBaseContent();
        Vars.mods = new Mods();

        Utils.runIgnoreError(Vars.content::init);

        Vars.state = new GameState();

        loadTextures();

        Core.batch = drawBatch;

        Utils.runIgnoreError(Vars.content::load);
        loadBlockColors();

        Vars.world = new World() {
            public Tile tile(int x, int y) {
                return new Tile(x, y);
            }
        };

        Lines.useLegacyLine = true;
        Draw.scl = 1f / 4f;

        Vars.modDirectory = new Fi(config.files().modsFolder());
        Vars.modDirectory.mkdirs();

        Vars.mods.load();
    }

    private void loadBlockColors() {
        var pixmap = new Pixmap(new Fi(config.files().assetsFolder() + "assets/sprites/block_colors.png"));
        for (int i = 0; i < pixmap.width; i++) {
            var block = Vars.content.block(i);
            if (block.itemDrop != null)
                block.mapColor.set(block.itemDrop.color);
            else
                block.mapColor.rgba8888(pixmap.get(i, 0)).a(1f);
        }
        pixmap.dispose();
    }

    private void loadTextures() {
        Core.atlas = new TextureAtlas();
        var data = new TextureAtlasData(new Fi(config.files().assetsFolder() + "assets/sprites/sprites.aatls"), new Fi(config.files().assetsFolder() + "assets/sprites"), false);

        data.getPages().each(page -> Utils.runIgnoreError(() -> {
            page.texture = Texture.createEmpty(null);
        }));
        data.getRegions().each(region -> Core.atlas.addRegion(region.name, new AtlasRegion(region.page.texture, region.left, region.top, region.width, region.height) {
            {
                name = region.name;
                texture = region.page.texture;
            }
        }));

        Core.atlas.setErrorRegion("error");
    }
}
