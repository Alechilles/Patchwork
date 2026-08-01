package com.alechilles.patchwork.engine;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/** One raw or host-expanded operation in a Patchwork definition. */
public final class PatchOperation {
    private final String id, op, path, position, macro;
    private final boolean required;
    private final JsonElement value;
    private final JsonObject find, existing, options;
    private PatchOperation(String id, String op, String path, String position, String macro, boolean required, JsonElement value, JsonObject find, JsonObject existing, JsonObject options) {
        this.id=id; this.op=op; this.path=path; this.position=position; this.macro=macro; this.required=required;
        this.value=copy(value); this.find=copy(find); this.existing=copy(existing); this.options=copy(options);
    }
    static PatchOperation parse(JsonObject object, String patchId, int index) {
        return new PatchOperation(PatchDefinition.readString(object,"Id",patchId+"#"+index), PatchDefinition.readRequiredString(object,"Op",patchId+" operation "+index), PatchDefinition.readString(object,"Path",null), PatchDefinition.readString(object,"Position",null), PatchDefinition.readString(object,"Macro",null), PatchDefinition.readBoolean(object,"Required",true), object.get("Value"), object(object,"Find"), object(object,"Existing"), object(object,"Options"));
    }
    /** Parses a host-expanded operation with a stable synthetic source position. */
    public static PatchOperation parseHostOperation(JsonObject object, String patchId) { return parse(object, patchId, 0); }
    /** Serializes this operation for the isolated host macro boundary. */
    public JsonObject toJson() {
        JsonObject object = new JsonObject(); object.addProperty("Id", id); object.addProperty("Op", op);
        if (path != null) object.addProperty("Path", path); if (position != null) object.addProperty("Position", position); if (macro != null) object.addProperty("Macro", macro);
        object.addProperty("Required", required); if (value != null) object.add("Value", value()); if (find != null) object.add("Find", find()); if (existing != null) object.add("Existing", existing()); if (options != null) object.add("Options", options()); return object;
    }
    /** Creates an explicit non-macro operation. */
    public static PatchOperation raw(String id, String op, String path, String position, boolean required, JsonElement value, JsonObject find, JsonObject existing) { return new PatchOperation(id,op,path,position,null,required,value,find,existing,null); }
    /** Returns this operation with the supplied macro identifier. */
    public PatchOperation withMacro(String macroId) { return new PatchOperation(id,op,path,position,macroId,required,value,find,existing,options); }
    public String id(){return id;} public String op(){return op;} public String path(){return path;} public String position(){return position;} public String macro(){return macro;} public boolean required(){return required;} public JsonElement value(){return copy(value);} public JsonObject find(){return copy(find);} public JsonObject existing(){return copy(existing);} public JsonObject options(){return copy(options);}
    public String getId(){return id();} public String getOp(){return op();} public String getPath(){return path();} public String getPosition(){return position();} public String getMacro(){return macro();} public boolean isRequired(){return required();} public JsonElement getValue(){return value();} public JsonObject getFind(){return find();} public JsonObject getExisting(){return existing();} public JsonObject getOptions(){return options();}
    private static JsonObject object(JsonObject parent,String name) { JsonElement value=parent.get(name); if(value==null||value.isJsonNull())return null; if(!value.isJsonObject())throw new IllegalArgumentException(name+" must be an object."); return value.getAsJsonObject(); }
    private static JsonElement copy(JsonElement value){return value==null?null:value.deepCopy();} private static JsonObject copy(JsonObject value){return value==null?null:value.deepCopy();}
}
