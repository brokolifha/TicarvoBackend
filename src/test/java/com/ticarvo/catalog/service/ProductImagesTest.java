package com.ticarvo.catalog.service;
import com.ticarvo.shared.exception.ApiException;
import org.junit.jupiter.api.Test;
import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import static org.junit.jupiter.api.Assertions.*;

class ProductImagesTest {
    @Test void jpegIsReencodedWithoutEmbeddedMetadata() throws Exception {
        byte[] withExif=withApp1(encode("jpg",64,48),"Exif\0\0GPS-SECRET-41.0082N");
        var result=ProductImages.process(withExif);
        assertEquals("image/jpeg",result.contentType());
        assertEquals(64,result.width()); assertEquals(48,result.height());
        assertFalse(new String(result.bytes(),StandardCharsets.ISO_8859_1).contains("GPS-SECRET"),"EXIF must be dropped");
        assertEquals(0xff,result.bytes()[0]&0xff); assertEquals(0xd8,result.bytes()[1]&0xff);
    }
    @Test void pngIsAccepted() throws Exception {
        var result=ProductImages.process(encode("png",10,10));
        assertEquals("image/png",result.contentType());
    }
    @Test void rejectsFakeCorruptOversizedAndHugeImages() throws Exception {
        assertInvalid("not an image".getBytes(StandardCharsets.UTF_8));
        byte[] png=encode("png",10,10);
        assertInvalid(Arrays.copyOf(png,20),"truncated PNG with a valid signature");
        assertInvalid(new byte[0]);
        assertInvalid(new byte[ProductImages.MAX_BYTES+1]);
        assertInvalid(encode("png",ProductImages.MAX_SIDE+1,1),"width over the limit");
        assertInvalid(encode("gif",10,10),"GIF is not accepted");
    }
    /** B-05: smaller copies keep the format and the aspect ratio; an image already small gets none. */
    @Test void resizedCopiesForLargeImagesOnly() throws Exception {
        var large=ProductImages.process(encode("png",2000,1000));
        assertEquals(java.util.Set.of(320,1024),large.variants().keySet());
        var thumb=ImageIO.read(new java.io.ByteArrayInputStream(large.variants().get(320)));
        assertEquals(320,thumb.getWidth()); assertEquals(160,thumb.getHeight());
        assertTrue(large.variants().get(320).length<large.bytes().length);
        var medium=ProductImages.process(encode("jpg",600,900));
        assertEquals(java.util.Set.of(320),medium.variants().keySet(),"no 1024 copy of a 900 px image");
        var copy=medium.variants().get(320);
        assertEquals(0xd8,copy[1]&0xff,"a JPEG copy stays JPEG");
        assertEquals(213,ImageIO.read(new java.io.ByteArrayInputStream(copy)).getWidth());
        assertTrue(ProductImages.process(encode("png",100,100)).variants().isEmpty());
    }
    static void assertInvalid(byte[] input,String... why) {
        assertEquals("IMAGE_INVALID",assertThrows(ApiException.class,()->ProductImages.process(input),String.join(" ",why)).code());
    }
    static byte[] encode(String format,int width,int height) throws Exception {
        var image=new BufferedImage(width,height,BufferedImage.TYPE_INT_RGB);
        image.setRGB(0,0,0x336699);
        var out=new ByteArrayOutputStream(); assertTrue(ImageIO.write(image,format,out)); return out.toByteArray();
    }
    /** Inserts an APP1 segment right after the JPEG SOI marker, like a camera's EXIF block. */
    static byte[] withApp1(byte[] jpeg,String payload) {
        byte[] data=payload.getBytes(StandardCharsets.ISO_8859_1); int length=data.length+2;
        var out=new ByteArrayOutputStream();
        out.write(jpeg,0,2); out.write(0xff); out.write(0xe1); out.write(length>>8); out.write(length&0xff);
        out.write(data,0,data.length); out.write(jpeg,2,jpeg.length-2);
        return out.toByteArray();
    }
}
