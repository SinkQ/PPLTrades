package ru.sink_q.ppltrades;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.StagedVertexBuffer;
import net.minecraft.client.renderer.rendertype.RenderSetup;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.Optional;
import java.util.OptionalDouble;

/**
 * Подсветка бочки, выбранной в окне предмета: полупрозрачный куб поверх всего (видно сквозь стены).
 *
 * Код отрисовки взят из мода Pochatok (renderer/SignBoxRenderer, MIT License,
 * Copyright (c) 2024 TheEvilM, https://modrinth.com/mod/pochatok) и адаптирован под одну бочку.
 */
public final class BarrelHighlight {
    // снимается при открытии бочки или повторном клике по лоту; таймаут — на случай, если забыли
    private static final long DURATION_MS = 5 * 60_000;
    private static final int COLOR = 0x602DD4BF; // ARGB: бирюзовый, полупрозрачный

    // без проверки глубины — куб виден сквозь блоки (как подсветка табличек в Pochatok)
    private static final RenderPipeline PIPELINE = RenderPipelines.register(
            RenderPipeline.builder(RenderPipelines.DEBUG_FILLED_SNIPPET)
                    .withLocation(Identifier.parse(Ppltrades.MOD_ID + ":pipeline/barrel_highlight"))
                    .withCull(false)
                    .withDepthStencilState(Optional.empty())
                    .build());
    private static final RenderType BOX = RenderType.create(Ppltrades.MOD_ID + ":barrel_highlight",
            RenderSetup.builder(PIPELINE).createRenderSetup());

    private static BlockPos target;
    private static ClientLevel level;
    private static long untilMs;

    private BarrelHighlight() {
    }

    public static void register() {
        LevelRenderEvents.END_MAIN.register(context -> render(context.poseStack()));
    }

    /** Подсветить бочку (в текущем мире). */
    public static void show(BlockPos pos) {
        Minecraft mc = Minecraft.getInstance();
        target = pos.immutable();
        level = mc.level;
        untilMs = System.currentTimeMillis() + DURATION_MS;
    }

    public static boolean isShown(BlockPos pos) {
        return target != null && target.equals(pos) && System.currentTimeMillis() <= untilMs;
    }

    public static void clear() {
        target = null;
    }

    /** Игрок открыл эту бочку — подсветка выполнила свою задачу. */
    public static void onBarrelOpened(BlockPos pos) {
        if (pos != null && pos.equals(target)) {
            target = null;
        }
    }

    private static void render(PoseStack poseStack) {
        Minecraft mc = Minecraft.getInstance();
        if (target == null) {
            return;
        }
        if (mc.level == null || mc.level != level || System.currentTimeMillis() > untilMs) {
            target = null; // истекло или сменился мир
            return;
        }

        try (StagedVertexBuffer buffer = new StagedVertexBuffer(() -> Ppltrades.MOD_ID, 4096)) {
            StagedVertexBuffer.Draw draw = buffer.appendDraw(BOX.format(), BOX.primitiveTopology(),
                    BOX.sortOnUpload() ? RenderSystem.getProjectionType().vertexSorting() : null);
            VertexConsumer consumer = buffer.getVertexBuilder(draw);
            Vec3 camera = mc.gameRenderer.mainCamera().position().reverse();
            drawBox(new AABB(target).inflate(0.02).move(camera), COLOR, poseStack, consumer);
            buffer.upload();
            StagedVertexBuffer.ExecuteInfo info = buffer.getExecuteInfo(draw);
            if (info != null) {
                RenderTarget main = mc.gameRenderer.mainRenderTarget();
                try (RenderPass pass = RenderSystem.getDevice().createCommandEncoder().createRenderPass(
                        () -> Ppltrades.MOD_ID + ":barrel_highlight", main.getColorTextureView(), Optional.empty(),
                        main.getDepthTextureView(), OptionalDouble.empty())) {
                    RenderSystem.bindDefaultUniforms(pass);
                    BOX.prepare().drawFromBuffer(info, pass);
                }
            }
            buffer.endDraw();
        }
    }

    /** 6 граней куба квадами (порядок вершин как в Pochatok SignBoxRenderer.drawSignBox). */
    private static void drawBox(AABB box, int color, PoseStack poseStack, VertexConsumer c) {
        PoseStack.Pose pose = poseStack.last();
        float x1 = (float) box.minX, y1 = (float) box.minY, z1 = (float) box.minZ;
        float x2 = (float) box.maxX, y2 = (float) box.maxY, z2 = (float) box.maxZ;
        float[][] quads = {
                {x1, y1, z1, x2, y1, z1, x2, y1, z2, x1, y1, z2},  // низ
                {x1, y2, z1, x1, y2, z2, x2, y2, z2, x2, y2, z1},  // верх
                {x1, y1, z1, x1, y2, z1, x2, y2, z1, x2, y1, z1},  // север
                {x2, y1, z1, x2, y2, z1, x2, y2, z2, x2, y1, z2},  // восток
                {x1, y1, z2, x2, y1, z2, x2, y2, z2, x1, y2, z2},  // юг
                {x1, y1, z1, x1, y1, z2, x1, y2, z2, x1, y2, z1},  // запад
        };
        for (float[] q : quads) {
            for (int i = 0; i < 12; i += 3) {
                c.addVertex(pose, q[i], q[i + 1], q[i + 2]).setColor(color);
            }
        }
    }
}
