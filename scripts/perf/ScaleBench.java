import java.nio.file.*;
import org.jetbrains.skia.*;

/** Times the exact work ScaledBitmapPainter does on the UI thread the first time a poster is drawn. */
public class ScaleBench {
    public static void main(String[] args) throws Exception {
        int dstW = Integer.parseInt(args[0]), dstH = Integer.parseInt(args[1]);
        for (int a = 2; a < args.length; a++) {
            byte[] bytes = Files.readAllBytes(Paths.get(args[a]));
            Image encoded = Image.Companion.makeFromEncoded(bytes);
            int w = encoded.getWidth(), h = encoded.getHeight();
            // Coil decodes to fit 1536 px on the long side (MaxDesktopSourceSizePx), never upscaling.
            double f = Math.min(1.0, 1536.0 / Math.max(w, h));
            int sw = (int) Math.round(w * f), sh = (int) Math.round(h * f);
            Bitmap source = new Bitmap();
            source.allocN32Pixels(sw, sh, false);
            Canvas c = new Canvas(source, new SurfaceProps());
            c.drawImageRect(encoded, Rect.Companion.makeWH(sw, sh));
            c.close();
            long copyNs = 0, scaleNs = 0;
            int iters = 15;
            for (int i = 0; i < iters + 3; i++) {
                long t0 = System.nanoTime();
                Image img = Image.Companion.makeFromBitmap(source);          // SkiaImage.makeFromBitmap(asSkiaBitmap())
                long t1 = System.nanoTime();
                Bitmap dst = new Bitmap();
                dst.allocN32Pixels(dstW, dstH, false);
                img.scalePixels(dst.peekPixels(), new FilterMipmap(FilterMode.LINEAR, MipmapMode.LINEAR), false);
                long t2 = System.nanoTime();
                img.close(); dst.close();
                if (i >= 3) { copyNs += t1 - t0; scaleNs += t2 - t1; }
            }
            System.out.printf("%s src=%dx%d decoded=%dx%d -> %dx%d : makeFromBitmap %.2f ms, scalePixels(mipmap) %.2f ms%n",
                Paths.get(args[a]).getFileName().toString().substring(0, 10), w, h, sw, sh, dstW, dstH,
                copyNs / 1e6 / iters, scaleNs / 1e6 / iters);
        }
    }
}
