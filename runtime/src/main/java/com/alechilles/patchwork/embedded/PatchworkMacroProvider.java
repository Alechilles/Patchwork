package com.alechilles.patchwork.embedded;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

/** Converts one host-defined macro operation into ordinary Patchwork operations. */
public interface PatchworkMacroProvider { String macroId(); JsonArray expand(JsonObject operation); }
