package com.landosol.toolbox.desktop.ocr

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.File
import java.nio.FloatBuffer
import javax.imageio.ImageIO
import org.junit.Test

/** Explicit diagnosePreprocessing task only; transformed images are never evaluation cases. */
class OcrFailureDiagnosisTest {
    @Test fun inspect() {
        val root = File("../..")
        val output = File("build/reports/preprocessing-diagnosis").apply { mkdirs() }
        val rows = mutableListOf("sample\tvariant\tstage\tbox\ttext\tconfidence")
        OnnxBackend().use { backend ->
            val detect = OnnxBackend::class.java.getDeclaredMethod("detect", OcrImage::class.java).apply { isAccessible = true }
            val session = OnnxBackend::class.java.getDeclaredField("recognition").apply { isAccessible = true }.get(backend) as OrtSession
            @Suppress("UNCHECKED_CAST")
            val dictionary = OnnxBackend::class.java.getDeclaredField("dictionary").apply { isAccessible = true }.get(backend) as List<String>
            fun rec(image: OcrImage): Pair<String, Float> {
                val input = OcrPreprocessing.recognition(image)
                OnnxTensor.createTensor(OrtEnvironment.getEnvironment(), FloatBuffer.wrap(input.values), longArrayOf(1, 3, 48, input.width.toLong())).use { tensor ->
                    session.run(mapOf(session.inputNames.single() to tensor)).use { result ->
                        val t = result[0] as OnnxTensor
                        val shape = t.info.shape
                        val values = FloatArray(t.floatBuffer.remaining()).also { t.floatBuffer.get(it) }
                        var prior = -1; var sum = 0f; var count = 0
                        val text = StringBuilder()
                        repeat(shape[1].toInt()) { step ->
                            val offset = step * dictionary.size
                            var best = 0
                            for (c in 1 until dictionary.size) if (values[offset + c] > values[offset + best]) best = c
                            if (best != 0 && best != prior) { text.append(dictionary[best]); sum += values[offset + best]; count++ }
                            prior = best
                        }
                        return text.toString() to if (count == 0) 0f else sum / count
                    }
                }
            }
            fun inspect(id: String, variant: String, image: OcrImage) {
                val bi = BufferedImage(image.width, image.height, BufferedImage.TYPE_INT_RGB)
                bi.setRGB(0, 0, image.width, image.height, image.pixels, 0, image.width)
                ImageIO.write(bi, "png", File(output, "$id-$variant.png"))
                val direct = rec(image)
                rows += "$id\t$variant\tdirect\t${image.width}x${image.height}\t${direct.first}\t${direct.second}"
                @Suppress("UNCHECKED_CAST") val boxes = detect.invoke(backend, image) as List<TextBox>
                for ((index, box) in boxes.withIndex()) {
                    val crop = image.crop(box)
                    val result = rec(crop)
                    rows += "$id\t$variant\tdetected-$index\t$box\t${result.first}\t${result.second}"
                    val cropBi = BufferedImage(crop.width, crop.height, BufferedImage.TYPE_INT_RGB)
                    cropBi.setRGB(0, 0, crop.width, crop.height, crop.pixels, 0, crop.width)
                    ImageIO.write(cropBi, "png", File(output, "$id-$variant-box$index.png"))
                }
                rows += "$id\t$variant\tfinal\t${boxes.size}\t${backend.recognize(image).replace("\n", "|")}\t"
                if (id == "series-visible" && variant in listOf("nearest","gray")) {
                    val detSession = OnnxBackend::class.java.getDeclaredField("detection").apply { isAccessible = true }.get(backend) as OrtSession
                    val input = OcrPreprocessing.detection(image)
                    OnnxTensor.createTensor(OrtEnvironment.getEnvironment(),FloatBuffer.wrap(input.values),longArrayOf(1,3,input.height.toLong(),input.width.toLong())).use { tensor ->
                        detSession.run(mapOf(detSession.inputNames.single() to tensor)).use { result ->
                            val t = result[0] as OnnxTensor
                            val values = FloatArray(t.floatBuffer.remaining()).also { t.floatBuffer.get(it) }
                            for ((label,box) in listOf("digit2" to TextBox(130,220,195,295), "digit1a" to TextBox(135,620,195,695), "digit1b" to TextBox(135,1020,195,1095))) {
                                val region=mutableListOf<Float>()
                                for(y in box.top*input.height/image.height until box.bottom*input.height/image.height) for(x in box.left*input.width/image.width until box.right*input.width/image.width) region += values[y*input.width+x]
                                rows += "$id\t$variant\tdet-map-$label\t$box\tmax=${region.maxOrNull()};mean=${region.average()};above0.3=${region.count { it>.3f }};above0.6=${region.count { it>.6f }}\t"
                            }
                        }
                    }
                }
                File(output, "diagnosis.tsv").writeText(rows.joinToString("\n"))
            }
            val samples = listOf(
                Triple("map-link-0", "labyrinth/node-link-20260914.png", intArrayOf(153,411,20,21,4)),
                Triple("map-link-1", "labyrinth/node-link-20260914.png", intArrayOf(190,411,20,21,4)),
                Triple("series-visible", "labyrinth/series-effect-panel-20261012.jpg", intArrayOf(532,150,120,713,2)),
                Triple("map-boss-0", "labyrinth/final-boss-active-20260909.jpg", intArrayOf(360,970,48,48,4)),
                Triple("map-boss-1", "labyrinth/final-boss-active-20260909.jpg", intArrayOf(448,970,48,48,4)),
                Triple("map-boss-2", "labyrinth/final-boss-active-20260909.jpg", intArrayOf(536,970,48,48,4)),
                Triple("map-boss-3", "labyrinth/final-boss-active-20260909.jpg", intArrayOf(624,970,48,48,4)),
                Triple("control-plus", "labyrinth/final-boss-active-20260909.jpg", intArrayOf(420,944,42,40,4)),
                Triple("control-cross", "labyrinth/final-boss-active-20260909.jpg", intArrayOf(505,944,42,40,4)),
                Triple("control-swords", "labyrinth/final-boss-active-20260909.jpg", intArrayOf(590,944,42,40,4)),
                Triple("control-chevron", "labyrinth/final-boss-active-20260909.jpg", intArrayOf(420,1008,48,24,4)),
                Triple("control-wall", "labyrinth/final-boss-active-20260909.jpg", intArrayOf(1110,765,48,48,4)),
            )
            for ((id, path, roi) in samples) {
                val source = ImageIO.read(File(root, "android/app/src/test/resources/$path"))
                val raw = source.getSubimage(roi[0],roi[1],roi[2],roi[3])
                for ((name, interpolation) in listOf("nearest" to RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR, "bilinear" to RenderingHints.VALUE_INTERPOLATION_BILINEAR, "bicubic" to RenderingHints.VALUE_INTERPOLATION_BICUBIC)) {
                    if ((id.startsWith("control") || id.startsWith("map-boss")) && name != "nearest") continue
                    val bi = BufferedImage(raw.width*roi[4], raw.height*roi[4], BufferedImage.TYPE_INT_RGB)
                    bi.createGraphics().apply { setRenderingHint(RenderingHints.KEY_INTERPOLATION,interpolation); drawImage(raw,0,0,bi.width,bi.height,null); dispose() }
                    val image = OcrImage(bi.width,bi.height,bi.getRGB(0,0,bi.width,bi.height,null,0,bi.width))
                    inspect(id,name,image)
                    if (name == "nearest") {
                        val gray = image.copy(pixels=image.pixels.map { p -> val v=(.299*((p ushr 16) and 255)+.587*((p ushr 8) and 255)+.114*(p and 255)).toInt(); -0x1000000 or (v shl 16) or (v shl 8) or v }.toIntArray())
                        inspect(id,"gray",gray)
                        inspect(id,"invert",gray.copy(pixels=gray.pixels.map { p -> -0x1000000 or ((p and 0xffffff) xor 0xffffff) }.toIntArray()))
                        for (factor in listOf(0.5,1.5)) inspect(id,"contrast$factor",gray.copy(pixels=gray.pixels.map { p -> val v=((p and 255)-128).times(factor).plus(128).toInt().coerceIn(0,255); -0x1000000 or (v shl 16) or (v shl 8) or v }.toIntArray()))
                        for ((colorName,color) in listOf("white" to -1,"black" to -0x1000000)) {
                            val margin = image.width / 4
                            val width = image.width + margin * 2
                            val height = image.height + margin * 2
                            val pixels = IntArray(width*height) { color }
                            repeat(image.height) { y -> image.pixels.copyInto(pixels,(y+margin)*width+margin,y*image.width,(y+1)*image.width) }
                            inspect(id,"margin-$colorName",OcrImage(width,height,pixels))
                        }
                        if (id == "series-visible") {
                            for ((colorName,color) in listOf("white" to -1,"black" to -0x1000000)) {
                                val margin=image.width/4
                                val width=image.width+margin*2
                                val pixels=IntArray(width*image.height) { color }
                                repeat(image.height) { y -> image.pixels.copyInto(pixels,y*width+margin,y*image.width,(y+1)*image.width) }
                                inspect(id,"sides-$colorName",OcrImage(width,image.height,pixels))
                            }
                            for ((channelName,shift) in listOf("red" to 16,"green" to 8,"blue" to 0)) inspect(id,channelName,image.copy(pixels=image.pixels.map { p -> val v=(p ushr shift) and 255; -0x1000000 or (v shl 16) or (v shl 8) or v }.toIntArray()))
                            for (threshold in listOf(150,200)) inspect(id,"binary$threshold",gray.copy(pixels=gray.pixels.map { p -> if ((p and 255)>=threshold) -1 else -0x1000000 }.toIntArray()))
                            for (tileSize in listOf(640,320)) {
                                var top=0
                                while(top < image.height) {
                                    val bottom = minOf(image.height,top+tileSize)
                                    inspect(id,"tile$tileSize-y$top",image.crop(TextBox(0,top,image.width,bottom)))
                                    top += tileSize-64
                                }
                            }
                            for ((label,box) in listOf("digit2" to TextBox(130,220,195,295), "digit1a" to TextBox(135,620,195,695), "digit1b" to TextBox(135,1020,195,1095))) inspect(id,label,image.crop(box))
                        } else {
                            val box = TextBox(image.width/3,0,image.width,image.height)
                            inspect(id,"right-two-thirds",image.crop(box))
                        }
                    }
                }
            }
        }
    }
}
