package com.comfyremote.panel;

import android.util.Log;
import org.json.JSONObject;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.function.Function;

/** Rebuildable metadata only. Workflow graphs remain the source of truth. */
final class WorkflowMetadataIndex {
    private static final Map<String, State> CACHE = new LinkedHashMap<String, State>(4, .75f, true) {
        @Override protected boolean removeEldestEntry(Map.Entry<String, State> entry) { return size() > 3; }
    };
    private static final class State {
        JSONObject entries = new JSONObject();
        long modified = -1, size = -1;
        boolean dirty;
    }
    private WorkflowMetadataIndex() {}
    private static File index(File directory) { return new File(new File(directory, "index_v3"), "library.json"); }
    private static State state(File directory) {
        String key=directory.getAbsolutePath(); File file=index(directory);
        State value=CACHE.get(key);
        if(value!=null && (value.dirty || (value.modified==file.lastModified() && value.size==file.length()))) return value;
        value=new State();
        if(file.isFile() && file.length()<=12L*1024*1024) try {
            JSONObject document=new JSONObject(new String(Files.readAllBytes(file.toPath()),StandardCharsets.UTF_8));
            if(document.optInt("version")==2 && document.optJSONObject("entries")!=null)
                value.entries=document.getJSONObject("entries");
        } catch(Exception error){Log.w("WorkflowIndex","列表索引将重新生成",error);}
        value.modified=file.lastModified();value.size=file.length();CACHE.put(key,value);return value;
    }
    static synchronized boolean ready(File directory) {
        State state=state(directory);File[] files=directory.listFiles((d,n)->n.endsWith(".json"));
        if(files==null)return true;
        for(File file:files)if(!matches(state.entries.optJSONObject(file.getName()),file))return false;
        return true;
    }
    static synchronized List<WorkflowProfile> load(File directory,Function<File,WorkflowProfile> reader) {
        State state=state(directory);JSONObject next=new JSONObject();List<WorkflowProfile> result=new ArrayList<>();
        File[] files=directory.listFiles((d,n)->n.endsWith(".json"));boolean changed=false;
        if(files!=null)for(File file:files){
            JSONObject entry=state.entries.optJSONObject(file.getName());
            WorkflowProfile profile;
            if(matches(entry,file))profile=profile(entry.optJSONObject("profile"));
            else {profile=reader.apply(file);entry=profile==null?null:entry(file,profile);changed=true;}
            if(profile==null)continue;
            result.add(profile);try{next.put(file.getName(),entry);}catch(Exception impossible){throw new IllegalStateException(impossible);}
        }
        if(next.length()!=state.entries.length())changed=true;
        state.entries=next;
        if(changed)state.dirty=true;
        if(state.dirty)flush(directory);
        return result;
    }
    private static boolean matches(JSONObject entry,File file){
        return entry!=null && entry.optJSONObject("profile")!=null &&
                (entry.optJSONObject("profile").optString("id")+".json").equals(file.getName()) && entry.optLong("modified",-1)==file.lastModified()
                && entry.optLong("size",-1)==file.length();
    }
    static synchronized void record(File directory,File file,WorkflowProfile profile,boolean persist) {
        State state=state(directory);
        try{state.entries.put(file.getName(),entry(file,profile));state.dirty=true;}
        catch(Exception error){throw new IllegalStateException(error);}
        if(persist)flush(directory);
    }
    static synchronized void remove(File directory,String name){
        State state=state(directory);state.entries.remove(name);state.dirty=true;flush(directory);
    }
    static synchronized void flush(File directory){
        State state=state(directory);if(!state.dirty)return;
        File file=index(directory),temp=new File(file.getParentFile(),"library.tmp");
        try{
            if(!file.getParentFile().isDirectory()&&!file.getParentFile().mkdirs())throw new IOException("索引目录不可写");
            JSONObject document=new JSONObject().put("version",2).put("entries",state.entries);
            try(Writer out=Files.newBufferedWriter(temp.toPath(),StandardCharsets.UTF_8)){out.write(document.toString());}
            try{Files.move(temp.toPath(),file.toPath(),StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.ATOMIC_MOVE);}
            catch(AtomicMoveNotSupportedException unsupported){Files.move(temp.toPath(),file.toPath(),StandardCopyOption.REPLACE_EXISTING);}
            state.modified=file.lastModified();state.size=file.length();state.dirty=false;
        }catch(Exception error){Log.w("WorkflowIndex","无法写入可重建的列表索引",error);}
        finally{temp.delete();}
    }
    private static JSONObject entry(File file,WorkflowProfile value){
        try{return new JSONObject().put("modified",file.lastModified()).put("size",file.length()).put("profile",
                new JSONObject().put("id",value.id).put("name",value.name).put("folder_id",value.folderId)
                        .put("favorite",value.favorite).put("created_at",value.createdAt).put("updated_at",value.updatedAt)
                        .put("collection_review",value.metadataOnly?value.collectionReviewNeeded:
                                value.isCollection()&&value.collectionDetectionVersion!=WorkflowVariants.DETECTION_VERSION));}
        catch(Exception error){throw new IllegalStateException(error);}
    }
    private static WorkflowProfile profile(JSONObject value){
        if(value==null)return null;
        WorkflowProfile profile=new WorkflowProfile(value.optString("id"),value.optString("name"),"{}",null,
                value.optBoolean("favorite"),value.optLong("created_at"),value.optLong("updated_at"),new JSONObject());
        profile.folderId=value.optString("folder_id","");profile.metadataOnly=true;
        profile.collectionReviewNeeded=value.optBoolean("collection_review");return profile;
    }
}
