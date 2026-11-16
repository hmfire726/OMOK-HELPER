package com.hmfire.omokhelper;

import android.media.Image;

import com.hmfire.omokhelper.core.BoardReader;

import java.nio.ByteBuffer;

/** ImageReader가 준 RGBA_8888 이미지를 BoardReader가 읽을 수 있게 감싼다 (복사 없음) */
final class ImagePixels implements BoardReader.Pixels {
    private final ByteBuffer buffer;
    private final int rowStride;
    private final int pixelStride;
    private final int width;
    private final int height;

    ImagePixels(Image image) {
        Image.Plane plane = image.getPlanes()[0];
        buffer = plane.getBuffer();
        rowStride = plane.getRowStride();
        pixelStride = plane.getPixelStride();
        width = image.getWidth();
        height = image.getHeight();
    }

    @Override
    public int width() {
        return width;
    }

    @Override
    public int height() {
        return height;
    }

    @Override
    public int rgb(int x, int y) {
        int i = y * rowStride + x * pixelStride;
        return ((buffer.get(i) & 0xFF) << 16) | ((buffer.get(i + 1) & 0xFF) << 8) | (buffer.get(i + 2) & 0xFF);
    }
}
