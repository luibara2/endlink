package org.cloudburstmc.protocol.bedrock.data.diagnostics;

import lombok.Data;

@Data
public class MemoryCategoryCounter {

    private final Category category;
    private final long currentBytes;

    public enum Category {

        UNKNOWN,
        INVALID_SIZE_UNKNOWN,
        ACTOR,
        ACTOR_ANIMATION,
        ACTOR_RENDERING,
        BALANCER,
        BLOCK_TICKING_QUEUES,
        /**
         * @since v2168
         */
        BIOME_STORAGE,
        /**
         * @since v2168
         */
        BLOBS,
        CEREAL,
        CIRCUIT_SYSTEM,
        CLIENT,
        COMMANDS,
        DB_STORAGE,
        DEBUG,
        DOCUMENTATION,
        ECS_SYSTEMS,
        FMOD,
        FONTS,
        IM_GUI,
        INPUT,
        JSON_UI,
        JSON_UI_CONTROL_FACTORY_JSON,
        JSON_UI_CONTROL_TREE,
        JSON_UI_CONTROL_TREE_CONTROL_ELEMENT,
        JSON_UI_CONTROL_TREE_POPULATE_DATA_BINDING,
        JSON_UI_CONTROL_TREE_POPULATE_FOCUS,
        JSON_UI_CONTROL_TREE_POPULATE_LAYOUT,
        JSON_UI_CONTROL_TREE_POPULATE_OTHER,
        JSON_UI_CONTROL_TREE_POPULATE_SPRITE,
        JSON_UI_CONTROL_TREE_POPULATE_TEXT,
        JSON_UI_CONTROL_TREE_POPULATE_TTS,
        JSON_UI_CONTROL_TREE_VISIBILITY,
        JSON_UI_CREATE_UI,
        JSON_UI_DEFS,
        JSON_UI_LAYOUT_MANAGER,
        JSON_UI_LAYOUT_MANAGER_REMOVE_DEPENDENCIES,
        JSON_UI_LAYOUT_MANAGER_INIT_VARIABLE,
        LANGUAGES,
        LEVEL,
        LEVEL_STRUCTURES,
        LEVEL_CHUNK,
        LEVEL_CHUNK_GEN,
        LEVEL_CHUNK_GEN_THREAD_LOCAL,
        NETWORK,
        MARKETPLACE,
        MATERIAL_DRAGON_COMPILED_DEFINITION,
        MATERIAL_DRAGON_MATERIAL,
        MATERIAL_DRAGON_RESOURCE,
        MATERIAL_DRAGON_UNIFORM_MAP,
        MATERIAL_RENDER_MATERIAL,
        MATERIAL_RENDER_MATERIAL_GROUP,
        MATERIAL_VARIATION_MANAGER,
        MOLANG,
        ORE_UI,
        /**
         * @since v2168
         */
        ORE_UI_CLIENT,
        /**
         * @since v2168
         */
        PERSONA_PIECES,
        /**
         * @since v2168
         */
        PERSONA_ANIMATIONS,
        /**
         * @since v2168
         */
        PERSONA_TEXTURES,
        /**
         * @since v2168
         */
        PERSONA_CHARACTERS,
        /**
         * @since v2168
         */
        PERSONA_SKIN_PACKS,
        /**
         * @since v2168
         */
        PERSONA_REPO,
        PLAYER,
        RENDER_CHUNK,
        RENDER_CHUNK_INDEX_BUFFER,
        RENDER_CHUNK_VERTEX_BUFFER,
        RENDERING,
        /**
         * @since v2168
         */
        RENDERING_BGFX_INIT,
        /**
         * @since v2168
         */
        RENDERING_BGFX_START_FRAME,
        /**
         * @since v2168
         */
        RENDERING_BLOCK_TESSELLATOR,
        /**
         * @since v2168
         */
        RENDERING_END_FRAME,
        /**
         * @since v2168
         */
        RENDERING_GRAPHICS_TASKS_INIT,
        RENDERING_LIBRARY,
        /**
         * @since v2168
         */
        RENDERING_POLYGON_OPERATOR_POOL,
        /**
         * @since v2168
         */
        RENDERING_PBR_TEXTURE_DATA,
        /**
         * @since v2168
         */
        RENDERING_RENDER_REGISTRY,
        /**
         * @since v2168
         */
        RENDERING_SETUP,
        /**
         * @since v2168
         */
        RENDERING_VERTICES,
        REQUEST_LOG,
        RESOURCE_PACKS,
        SOUND,
        SUB_CHUNK_BIOME_DATA,
        SUB_CHUNK_BLOCK_DATA,
        SUB_CHUNK_LIGHT_DATA,
        TEXTURES,
        WEATHER_RENDERER,
        WORLD_GENERATOR,
        TASKS,
        TEST,
        /**
         * @since v2168
         */
        TEST_LOAD_TEST_TAGS,
        SCRIPTING,
        SCRIPTING_RUNTIME,
        SCRIPTING_CONTEXT,
        SCRIPTING_CONTEXT_BINDINGS_MC,
        SCRIPTING_CONTEXT_BINDINGS_GT,
        SCRIPTING_CONTEXT_RUN,
        DATA_DRIVEN_UI,
        DATA_DRIVEN_UI_DEFS,
        /**
         * @since v944
         */
        GAMEFACE,
        /**
         * @since v944
         */
        GAMEFACE_SYSTEM,
        /**
         * @since v944
         */
        GAMEFACE_DOM,
        /**
         * @since v944
         */
        GAMEFACE_CSS,
        /**
         * @since v944
         */
        GAMEFACE_DISPLAY,
        /**
         * @since v944
         */
        GAMEFACE_TEMP_ALLOCATOR,
        /**
         * @since v944
         */
        GAMEFACE_POOL_ALLOCATOR,
        /**
         * @since v944
         */
        GAMEFACE_DUMP,
        /**
         * @since v944
         */
        GAMEFACE_MEDIA,
        /**
         * @since v944
         */
        GAMEFACE_JSON,
        /**
         * @since v944
         */
        GAMEFACE_SCRIPT_ENGINE,
        /**
         * @since v2168
         */
        GAMEFACE_SCRIPT,
        /**
         * @since v2168
         */
        GAMEFACE_LAYOUT,
        /**
         * @deprecated since v2168
         */
        VR,
    }
}
