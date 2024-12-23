package mindustrytool.mindustrycontentparser.utils;

import arc.graphics.Texture;
import arc.graphics.g2d.SpriteBatch;
import arc.graphics.g2d.TextureAtlas.AtlasRegion;
import arc.graphics.g2d.TextureRegion;
import arc.math.Mathf;
import arc.struct.ObjectMap;
import arc.util.Tmp;
import mindustrytool.mindustrycontentparser.service.AssetsService;

import java.awt.Graphics2D;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import static arc.graphics.Color.white;

@Component
public class DrawBatch extends SpriteBatch {

    public static final ObjectMap<String, BufferedImage> regions = new ObjectMap<>();
    public static BufferedImage currentImage;
    public static Graphics2D currentGraphics;

    @Autowired
    private AssetsService schematicAssetsService;

    public DrawBatch() {
        super(0);
    }

    @Override
    protected void draw(TextureRegion region, float x, float y, float originX, float originY, float width, float height, float rotation) {
        x += 4;
        y += 4;
        x *= 4;
        y *= 4;

        AffineTransform transform = new AffineTransform();
        transform.translate(x, currentImage.getHeight() - height * 4f - y);
        transform.rotate(-rotation * Mathf.degRad, originX * 4, originY * 4);

        try {

            currentGraphics.setTransform(transform);
            BufferedImage image = schematicAssetsService.getAssetsByName(((AtlasRegion) region).name);
            currentGraphics.drawImage(recolorImage(image), 0, 0, Math.min((int) Math.ceil(width * 4), currentImage.getWidth()), Math.min((int) Math.ceil(height * 4), currentImage.getHeight()), null);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    @Override
    protected void draw(Texture texture, float[] spriteVertices, int offset, int count) {
    }

    public BufferedImage recolorImage(BufferedImage image) {
        if (color.equals(white))
            return image;

        BufferedImage copy = new BufferedImage(image.getWidth(), image.getHeight(), image.getType());

        for (int x = 0; x < copy.getWidth(); x++)
            for (int y = 0; y < copy.getHeight(); y++)
                copy.setRGB(x, y, Tmp.c1.argb8888(image.getRGB(x, y)).mul(color).argb8888());

        return copy;
    }
}
