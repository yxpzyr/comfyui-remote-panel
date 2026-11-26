package com.comfyremote.panel;

import org.json.*;
import java.util.*;

/** Reclassifies an old saved collection. Store writes only after compilation and backup. */
final class WorkflowCollectionRepair {
    private static final String[] MAPS={"overrides","input_uris","input_replace","input_mask_paths","enabled_aux_images","enabled_aux_loras"};
    private WorkflowCollectionRepair(){}
    static void repair(WorkflowProfile profile,String server)throws Exception{
        JSONObject ui=profile.uiObjectOrNull();JSONArray old=profile.variants;
        JSONArray detected=WorkflowVariants.detect(ui);
        profile.rememberActiveVariant();
        if(detected.length()==0){
            JSONObject prompt=WorkflowUiConverter.toApiPrompt(ui,server);
            for(Iterator<String> it=profile.variantStates.keys();it.hasNext();)mergeProfile(profile,profile.variantStates.optJSONObject(it.next()));
            profile.promptJson=prompt.toString();profile.variants=new JSONArray();profile.activeVariantId="";
            profile.setOutputChoices(WorkflowUtils.findOutputNodes(prompt),false,false);
        }else{
            JSONObject previous=find(old,profile.activeVariantId);
            JSONObject chosen=find(detected,profile.activeVariantId);
            if(chosen==null&&previous!=null)for(int i=0;i<detected.length();i++){
                JSONObject entry=detected.getJSONObject(i);
                if(containsGroup(entry,previous.optInt("group_index",-1))){chosen=entry;break;}
            }
            if(chosen==null)chosen=find(detected,WorkflowVariants.initialVariantId(detected));
            String selected=chosen.getString("id");
            for(int i=0;i<detected.length();i++){
                JSONObject entry=detected.getJSONObject(i),before=find(old,entry.getString("id"));
                if(sameOutputs(before,entry))continue;
                JSONObject state=profile.variantStates.optJSONObject(entry.getString("id"));if(state==null)state=new JSONObject();
                for(int j=0;j<old.length();j++){
                    JSONObject oldEntry=old.getJSONObject(j);
                    if(!containsGroup(entry,oldEntry.optInt("group_index",-1)))continue;
                    JSONObject source=profile.variantStates.optJSONObject(oldEntry.optString("id"));
                    if(source!=null)for(String key:MAPS){
                        JSONObject target=state.optJSONObject(key);if(target==null){target=new JSONObject();state.put(key,target);}
                        mergeMissing(target,source.optJSONObject(key));
                    }
                }
                // Cached execution graphs must be rebuilt for changed output membership.
                state.remove("prompt_json");state.remove("output_nodes");state.remove("selected_outputs");state.remove("outputs_verified");
                profile.variantStates.put(entry.getString("id"),state);
            }
            if(!sameOutputs(previous,chosen)){
                mergeProfile(profile,profile.variantStates.optJSONObject(selected));
                Set<String> images=new HashSet<>();
                for(Iterator<String> it=profile.enabledAuxImages.keys();it.hasNext();){String id=it.next();if(profile.isAuxImageEnabled(id))images.add(id);}
                JSONObject prompt=WorkflowVariants.compile(ui,detected,selected,server,images,profile.auxLoRAIds());
                profile.promptJson=prompt.toString();
                profile.setOutputChoices(WorkflowVariants.finalOutputs(prompt,detected,selected,null),false,false);
            }
            profile.variants=detected;profile.activeVariantId=selected;
        }
        profile.collectionDetectionVersion=WorkflowVariants.DETECTION_VERSION;profile.collectionReviewNeeded=false;
    }
    private static void mergeProfile(WorkflowProfile p,JSONObject source)throws Exception{
        if(source==null)return;
        mergeMissing(p.overrides,source.optJSONObject("overrides"));mergeMissing(p.inputUris,source.optJSONObject("input_uris"));
        mergeMissing(p.inputReplace,source.optJSONObject("input_replace"));mergeMissing(p.inputMaskPaths,source.optJSONObject("input_mask_paths"));
        mergeMissing(p.enabledAuxImages,source.optJSONObject("enabled_aux_images"));mergeMissing(p.enabledAuxLoras,source.optJSONObject("enabled_aux_loras"));
    }
    private static void mergeMissing(JSONObject target,JSONObject source)throws Exception{
        if(source==null)return;for(Iterator<String> it=source.keys();it.hasNext();){String key=it.next();if(!target.has(key))target.put(key,source.get(key));}
    }
    private static JSONObject find(JSONArray entries,String id){
        for(int i=0;i<entries.length();i++){JSONObject entry=entries.optJSONObject(i);if(entry!=null&&id.equals(entry.optString("id")))return entry;}return null;
    }
    private static boolean containsGroup(JSONObject entry,int group){
        JSONArray groups=entry.optJSONArray("group_indices");
        if(groups==null)return entry.optInt("group_index",-2)==group;
        for(int i=0;i<groups.length();i++)if(groups.optInt(i,-2)==group)return true;return false;
    }
    private static boolean sameOutputs(JSONObject a,JSONObject b){
        if(a==null||b==null)return false;
        return outputSet(a).equals(outputSet(b));
    }
    private static Set<String> outputSet(JSONObject entry){
        Set<String> result=new HashSet<>();JSONArray array=entry.optJSONArray("outputs");
        if(array!=null)for(int i=0;i<array.length();i++)result.add(array.optString(i));return result;
    }
}
