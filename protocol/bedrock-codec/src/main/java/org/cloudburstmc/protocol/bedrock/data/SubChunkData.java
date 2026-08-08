package org.cloudburstmc.protocol.bedrock.data;

import io.netty.buffer.ByteBuf;
import io.netty.util.AbstractReferenceCounted;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.cloudburstmc.math.vector.Vector3i;

@Data
@EqualsAndHashCode(callSuper = false)
public class SubChunkData extends AbstractReferenceCounted {
    private Vector3i position;
    private ByteBuf data;
    private SubChunkRequestResult result;
    private HeightMapDataType heightMapType;
    private ByteBuf heightMapData;
    private HeightMapDataType renderHeightMapType;
    private ByteBuf renderHeightMapData;
    private boolean cacheEnabled;
    @Nullable
    private Long blobId;

    @Override
    public SubChunkData touch(Object o) {
        if (this.data != null) {
            this.data.touch(o);
        }
        if (this.heightMapData != null) {
            this.heightMapData.touch(o);
        }
        // Local delta: upstream omits renderHeightMapData from both touch and deallocate, so the
        // retained slice taken in SubChunkSerializer_v486.deserializeSubChunk is never released. Every
        // sub-chunk carrying a render heightmap leaks 256 bytes of direct memory, and a proxy decodes
        // far more of them than a client does. Keep this through upstream syncs.
        if (this.renderHeightMapData != null) {
            this.renderHeightMapData.touch(o);
        }
        return this;
    }

    @Override
    protected void deallocate() {
        if (this.data != null) {
            this.data.release();
        }
        if (this.heightMapData != null) {
            this.heightMapData.release();
        }
        if (this.renderHeightMapData != null) {
            this.renderHeightMapData.release();
        }
    }
}
