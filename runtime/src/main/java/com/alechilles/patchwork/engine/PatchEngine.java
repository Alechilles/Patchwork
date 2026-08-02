package com.alechilles.patchwork.engine;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/** Applies Patchwork operations to a copied JSON asset without host-plugin dependencies. */
public final class PatchEngine {
    private final PatchMacroRegistry macroRegistry;
    /** Creates an engine with no registered host macros. */ public PatchEngine(){this(new PatchMacroRegistry());}
    /** Creates an engine using the supplied host macro registry. */ public PatchEngine(PatchMacroRegistry macroRegistry){this.macroRegistry=macroRegistry;}
    /** Applies enabled definitions in deterministic order and returns patched JSON plus diagnostics. */
    public PatchResult apply(JsonObject source,List<PatchDefinition> definitions){
        JsonObject working=source.deepCopy(); List<String> applied=new ArrayList<>(),skipped=new ArrayList<>();
        definitions.stream().filter(PatchDefinition::enabled).sorted(PatchDefinition.ORDERING).forEach(definition->{
            for(PatchOperation operation:definition.operations()) for(PatchOperation raw:macroRegistry.expand(operation)) apply(working,definition,raw,applied,skipped);
        }); return new PatchResult(working,List.copyOf(applied),List.copyOf(skipped));
    }
    private static void apply(JsonObject root,PatchDefinition definition,PatchOperation operation,List<String> applied,List<String> skipped){
        String label=definition.id()+":"+operation.id(); try {String skip=raw(root,definition,operation);if(skip==null)applied.add(label);else skipped.add(label+" ("+skip+")");}
        catch(RuntimeException ex){String message=label+" failed: "+ex.getMessage();if(operation.required())throw new PatchFailureException(message,ex);skipped.add(message);}
    }
    private static String raw(JsonObject root,PatchDefinition definition,PatchOperation operation){return switch(operation.op().toLowerCase(Locale.ROOT)){
        case "requireformat" -> {
            if (definition.formatVersion() == 2 && operation.formatVersion() == 2
                    && Integer.valueOf(definition.formatVersion()).equals(operation.version())) yield null;
            if (definition.formatVersion() != 2 || operation.formatVersion() != 2) {
                throw new IllegalArgumentException("Unsupported operation '" + operation.op() + "'.");
            }
            throw new IllegalArgumentException("RequireFormat version does not match definition format version.");
        }
        case "add" -> {add(root,operation);yield null;} case "merge" -> {merge(root,operation);yield null;} case "replace" -> {replace(root,operation);yield null;} case "remove" -> {remove(root,operation);yield null;} case "insert" -> insert(root,operation); default -> throw new IllegalArgumentException("Unsupported operation '"+operation.op()+"'.");};}
    private static void add(JsonObject root,PatchOperation operation){PathTarget target=parent(root,path(operation),true);JsonElement value=value(operation);if(target.parent().isJsonObject())target.parent().getAsJsonObject().add(target.leaf(),value);else if(target.parent().isJsonArray()){JsonArray array=target.parent().getAsJsonArray();insert(array,arrayIndex(target.leaf(),array.size(),true),value);}else throw new IllegalArgumentException("Add parent is not an object or array at "+operation.path()+".");}
    private static void merge(JsonObject root,PatchOperation operation){JsonElement value=value(operation);if(!value.isJsonObject())throw new IllegalArgumentException("Merge value must be an object.");JsonElement target=resolve(root,path(operation));if(target==null||!target.isJsonObject())throw new IllegalArgumentException("Merge target must exist and be an object at "+operation.path()+".");merge(target.getAsJsonObject(),value.getAsJsonObject());}
    private static void replace(JsonObject root,PatchOperation operation){PathTarget target=parent(root,path(operation),false);JsonElement value=value(operation);if(target.parent().isJsonObject()){target.parent().getAsJsonObject().add(target.leaf(),value);return;}if(target.parent().isJsonArray()){JsonArray array=target.parent().getAsJsonArray();array.set(arrayIndex(target.leaf(),array.size(),false),value);return;}throw new IllegalArgumentException("Replace parent is not an object or array at "+operation.path()+".");}
    private static void remove(JsonObject root,PatchOperation operation){PathTarget target=parent(root,path(operation),false);if(target.parent().isJsonObject()){if(target.parent().getAsJsonObject().remove(target.leaf())==null)throw new IllegalArgumentException("Remove target does not exist at "+operation.path()+".");return;}if(target.parent().isJsonArray()){JsonArray array=target.parent().getAsJsonArray();array.remove(arrayIndex(target.leaf(),array.size(),false));return;}throw new IllegalArgumentException("Remove parent is not an object or array at "+operation.path()+".");}
    private static String insert(JsonObject root,PatchOperation operation){JsonElement target=resolve(root,path(operation));if(target==null||!target.isJsonArray())throw new IllegalArgumentException("Insert target must be an array at "+operation.path()+".");JsonArray array=target.getAsJsonArray();if(operation.existing()!=null&&find(array,operation.existing())>=0)return "existing matcher already present";String position=operation.position()==null?"End":operation.position();int index=switch(position.toLowerCase(Locale.ROOT)){case "start"->0;case "end"->array.size();case "before"->anchor(array,operation,false);case "after"->anchor(array,operation,true);default->throw new IllegalArgumentException("Unsupported insert position '"+position+"'.");};insert(array,index,value(operation));return null;}
    private static int anchor(JsonArray array,PatchOperation operation,boolean after){if(operation.find()==null)throw new IllegalArgumentException("Insert "+operation.position()+" requires Find.");int index=find(array,operation.find());if(index<0)throw new IllegalArgumentException("Insert anchor not found for "+operation.id()+".");return after?index+1:index;}
    static boolean matches(JsonElement candidate,JsonObject matcher){JsonElement contains=matcher.get("$Contains");if(contains!=null){if(candidate==null||!candidate.isJsonArray()||!contains.isJsonObject())return false;for(JsonElement entry:candidate.getAsJsonArray())if(matches(entry,contains.getAsJsonObject()))return true;return false;}if(candidate==null||!candidate.isJsonObject())return false;for(Map.Entry<String,JsonElement> entry:matcher.entrySet()){JsonElement actual=candidate.getAsJsonObject().get(entry.getKey()),expected=entry.getValue();if(expected.isJsonObject()){if(!matches(actual,expected.getAsJsonObject()))return false;}else if(actual==null||!actual.equals(expected))return false;}return true;}
    private static int find(JsonArray array,JsonObject matcher){for(int i=0;i<array.size();i++)if(matches(array.get(i),matcher))return i;return -1;}
    private static void merge(JsonObject target,JsonObject value){for(Map.Entry<String,JsonElement> entry:value.entrySet()){JsonElement existing=target.get(entry.getKey()),incoming=entry.getValue();if(existing!=null&&existing.isJsonObject()&&incoming!=null&&incoming.isJsonObject())merge(existing.getAsJsonObject(),incoming.getAsJsonObject());else target.add(entry.getKey(),incoming==null?null:incoming.deepCopy());}}
    private static JsonElement resolve(JsonElement root,String path){JsonElement current=root;for(String token:tokens(path)){if(current==null)return null;if(current.isJsonObject())current=current.getAsJsonObject().get(token);else if(current.isJsonArray())current=current.getAsJsonArray().get(arrayIndex(token,current.getAsJsonArray().size(),false));else return null;}return current;}
    private static PathTarget parent(JsonObject root,String path,boolean allowMissingLeaf){List<String> tokens=tokens(path);if(tokens.isEmpty())throw new IllegalArgumentException("Path must not point to the document root.");JsonElement current=root;for(int i=0;i<tokens.size()-1;i++){String token=tokens.get(i);if(current.isJsonObject())current=current.getAsJsonObject().get(token);else if(current.isJsonArray())current=current.getAsJsonArray().get(arrayIndex(token,current.getAsJsonArray().size(),false));else throw new IllegalArgumentException("Path parent is not traversable at "+token+".");if(current==null)throw new IllegalArgumentException("Path parent does not exist at "+token+".");}String leaf=tokens.getLast();if(!allowMissingLeaf&&current.isJsonObject()&&!current.getAsJsonObject().has(leaf))throw new IllegalArgumentException("Path leaf does not exist at "+leaf+".");return new PathTarget(current,leaf);}
    private static List<String> tokens(String path){if(path.isBlank()||"/".equals(path))return List.of();if(!path.startsWith("/"))throw new IllegalArgumentException("Path must use JSON pointer syntax and start with '/': "+path);return java.util.Arrays.stream(path.substring(1).split("/",-1)).map(token->token.replace("~1","/").replace("~0","~")).toList();}
    private static int arrayIndex(String token,int size,boolean allowEnd){if("-".equals(token)&&allowEnd)return size;try{int index=Integer.parseInt(token),upper=allowEnd?size:size-1;if(index<0||index>upper)throw new IllegalArgumentException("Array index out of bounds: "+token+".");return index;}catch(NumberFormatException ex){throw new IllegalArgumentException("Array path token must be an integer: "+token+".",ex);}}
    private static void insert(JsonArray array,int index,JsonElement value){JsonArray rebuilt=new JsonArray();for(int i=0;i<array.size();i++){if(i==index)rebuilt.add(value);rebuilt.add(array.get(i));}if(index==array.size())rebuilt.add(value);while(!array.isEmpty())array.remove(0);for(JsonElement element:rebuilt)array.add(element);}
    private static String path(PatchOperation operation){if(operation.path()==null||operation.path().isBlank())throw new IllegalArgumentException("Operation "+operation.id()+" requires Path.");return operation.path();}
    private static JsonElement value(PatchOperation operation){JsonElement value=operation.value();if(value==null)throw new IllegalArgumentException("Operation "+operation.id()+" requires Value.");return value;}
    /** Patched JSON plus operation diagnostics from a patch run. */ public record PatchResult(JsonObject patched,List<String> applied,List<String> skipped) { }
    private record PathTarget(JsonElement parent,String leaf) { }
    /** Indicates failure of a required patch operation. */ public static final class PatchFailureException extends RuntimeException { public PatchFailureException(String message,Throwable cause){super(message,cause);} }
}
