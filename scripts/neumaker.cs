// Neumorphic nine-patch generator for 言出法随.
// Raised: dual blurred shadows (light TL / dark BR) + crisp surface.
// Inset: concave plate (dark TL inner shade + light BR inner shine).
// ASCII only. Compile: Add-Type -TypeDefinition (Get-Content neumaker.cs -Raw) -ReferencedAssemblies System.Drawing
using System;
using System.Drawing;
using System.Drawing.Drawing2D;
using System.Drawing.Imaging;
using System.Runtime.InteropServices;

public static class NeuMaker {

    static Color Hex(string h) {
        h = h.TrimStart('#');
        return Color.FromArgb(255,
            Convert.ToInt32(h.Substring(0, 2), 16),
            Convert.ToInt32(h.Substring(2, 2), 16),
            Convert.ToInt32(h.Substring(4, 2), 16));
    }

    static GraphicsPath RR(int x, int y, int w, int h, int r) {
        var p = new GraphicsPath();
        int d = r * 2;
        p.AddArc(x, y, d, d, 180, 90);
        p.AddArc(x + w - d, y, d, d, 270, 90);
        p.AddArc(x + w - d, y + h - d, d, d, 0, 90);
        p.AddArc(x, y + h - d, d, d, 90, 90);
        p.CloseFigure();
        return p;
    }

    static void BoxBlurBitmap(Bitmap b, int w, int h, int r, int passes) {
        var bd = b.LockBits(new Rectangle(0, 0, w, h), ImageLockMode.ReadWrite, PixelFormat.Format32bppArgb);
        int bytes = Math.Abs(bd.Stride) * h;
        byte[] px = new byte[bytes];
        byte[] tmp = new byte[bytes];
        Marshal.Copy(bd.Scan0, px, 0, bytes);
        for (int p = 0; p < passes; p++) {
            for (int y = 0; y < h; y++) {
                int row = y * w * 4;
                for (int c = 0; c < 4; c++) {
                    int sum = 0;
                    for (int x = -r; x <= r; x++) {
                        int xx = x < 0 ? 0 : (x >= w ? w - 1 : x);
                        sum += px[row + xx * 4 + c];
                    }
                    int win = 2 * r + 1;
                    for (int x = 0; x < w; x++) {
                        tmp[row + x * 4 + c] = (byte)(sum / win);
                        int xa = x + r + 1; if (xa >= w) xa = w - 1;
                        int xr = x - r; if (xr < 0) xr = 0;
                        sum += px[row + xa * 4 + c] - px[row + xr * 4 + c];
                    }
                }
            }
            for (int x = 0; x < w; x++) {
                for (int c = 0; c < 4; c++) {
                    int sum = 0;
                    for (int y = -r; y <= r; y++) {
                        int yy = y < 0 ? 0 : (y >= h ? h - 1 : y);
                        sum += px[(yy * w + x) * 4 + c];
                    }
                    int win = 2 * r + 1;
                    for (int y = 0; y < h; y++) {
                        tmp[(y * w + x) * 4 + c] = (byte)(sum / win);
                        int ya = y + r + 1; if (ya >= h) ya = h - 1;
                        int yr = y - r; if (yr < 0) yr = 0;
                        sum += px[(ya * w + x) * 4 + c] - px[(yr * w + x) * 4 + c];
                    }
                }
            }
            Buffer.BlockCopy(tmp, 0, px, 0, bytes);
        }
        Marshal.Copy(px, 0, bd.Scan0, bytes);
        b.UnlockBits(bd);
    }

    // mode: "raised" (dual soft shadows + crisp surface) | "inset" (concave plate)
    public static void Make(string outPath, int size, int pad, int rad, int off, int blur,
        string surfHex, string hiHex, string loHex, string mode) {

        Color surf = Hex(surfHex), hi = Hex(hiHex), lo = Hex(loHex);
        int cw = size - pad * 2, ch = size - pad * 2;

        Bitmap bmp = new Bitmap(size, size);
        using (Graphics g = Graphics.FromImage(bmp)) {
            g.SmoothingMode = SmoothingMode.AntiAlias;
            g.Clear(Color.Transparent);

            if (mode == "raised") {
                using (Bitmap sh = new Bitmap(size, size)) {
                    using (Graphics sg = Graphics.FromImage(sh)) {
                        sg.SmoothingMode = SmoothingMode.AntiAlias;
                        using (var b = new SolidBrush(lo))
                            using (var p = RR(pad + off, pad + off, cw, ch, rad)) g.FillPath(b, p);
                        using (var b = new SolidBrush(hi))
                            using (var p = RR(pad - off, pad - off, cw, ch, rad)) g.FillPath(b, p);
                    }
                    BoxBlurBitmap(sh, size, size, blur, 3);
                    g.DrawImage(sh, 0, 0, size, size);
                }
                using (var b = new SolidBrush(surf))
                    using (var p = RR(pad, pad, cw, ch, rad)) g.FillPath(b, p);
            }
            else {
                using (var b = new SolidBrush(surf))
                    g.FillRectangle(b, 0, 0, size, size);
                int deep = blur / 2 + 4;
                using (var p = RR(pad, pad, cw, ch, rad)) {
                    using (var lg = new LinearGradientBrush(
                        new Point(pad - deep, pad - deep),
                        new Point(pad + cw + deep, pad + ch + deep),
                        Color.FromArgb(150, lo), Color.FromArgb(0, lo)))
                        g.FillPath(lg, p);
                    using (var lg = new LinearGradientBrush(
                        new Point(pad + cw + deep, pad + ch + deep),
                        new Point(pad - deep, pad - deep),
                        Color.FromArgb(120, hi), Color.FromArgb(0, hi)))
                        g.FillPath(lg, p);
                }
            }
        }

        // 9-patch markers: 1px black on the border
        int midA = size / 2 - 45, midB = size / 2 + 45;
        for (int x = midA; x <= midB; x++) { bmp.SetPixel(x, 0, Color.Black); bmp.SetPixel(x, size - 1, Color.Black); }
        for (int y = midA; y <= midB; y++) { bmp.SetPixel(0, y, Color.Black); bmp.SetPixel(size - 1, y, Color.Black); }
        int pa = pad + 20, pb = size - pad - 20;
        for (int x = pa; x <= pb; x++) bmp.SetPixel(x, size - 1, Color.Black);
        for (int y = pa; y <= pb; y++) bmp.SetPixel(size - 1, y, Color.Black);

        // AAPT: non-marker border pixels must be fully transparent
        Color clear = Color.FromArgb(0, 0, 0, 0);
        for (int x = 0; x < size; x++) {
            bool top = (x >= midA && x <= midB);
            bool bot = (x >= pa && x <= pb);
            if (!top) bmp.SetPixel(x, 0, clear);
            if (!bot) bmp.SetPixel(x, size - 1, clear);
        }
        for (int y = 0; y < size; y++) {
            bool lef = (y >= midA && y <= midB);
            bool rig = (y >= pa && y <= pb);
            if (!lef) bmp.SetPixel(0, y, clear);
            if (!rig) bmp.SetPixel(size - 1, y, clear);
        }

        bmp.Save(outPath, ImageFormat.Png);
        bmp.Dispose();
        Console.WriteLine("saved: " + outPath + " (" + size + "x" + size + ", mode=" + mode + ")");
    }
}
