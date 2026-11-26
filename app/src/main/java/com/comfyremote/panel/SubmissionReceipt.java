package com.comfyremote.panel;

import android.util.JsonReader;
import android.util.JsonToken;
import java.io.*;
import java.nio.charset.StandardCharsets;

/** Finds an acknowledgement by client_id without retaining execution graphs. */
final class SubmissionReceipt {
    private SubmissionReceipt() {}
    static String read(InputStream stream, String client, boolean history) throws Exception {
        if(client==null||client.isEmpty())return "";
        InputStream bounded=new FilterInputStream(stream){
            long remaining=8L*1024*1024;
            @Override public int read()throws IOException{if(remaining<=0)throw new IOException("回执核对已达到流量上限");int n=super.read();if(n>=0)remaining--;return n;}
            @Override public int read(byte[] b,int off,int len)throws IOException{
                if(remaining<=0)throw new IOException("回执核对已达到流量上限");
                int n=in.read(b,off,(int)Math.min(len,remaining));if(n>0)remaining-=n;return n;
            }
        };
        try(JsonReader reader=new JsonReader(new InputStreamReader(bounded,StandardCharsets.UTF_8))){
            reader.beginObject();
            while(reader.hasNext()){
                String key=reader.nextName();
                if(history){
                    if(reader.peek()!=JsonToken.BEGIN_OBJECT){reader.skipValue();continue;}
                    reader.beginObject();
                    while(reader.hasNext()){
                        if("prompt".equals(reader.nextName())){String id=tuple(reader,client);if(!id.isEmpty())return id;}
                        else reader.skipValue();
                    }reader.endObject();
                }else if((key.equals("queue_running")||key.equals("queue_pending"))&&reader.peek()==JsonToken.BEGIN_ARRAY){
                    reader.beginArray();while(reader.hasNext()){String id=tuple(reader,client);if(!id.isEmpty())return id;}reader.endArray();
                }else reader.skipValue();
            }
            reader.endObject();return "";
        }
    }
    private static String tuple(JsonReader reader,String client)throws Exception{
        if(reader.peek()!=JsonToken.BEGIN_ARRAY){reader.skipValue();return "";}
        reader.beginArray();String id="",found="";int index=0;
        while(reader.hasNext()){
            if(index==1&&reader.peek()==JsonToken.STRING)id=reader.nextString();
            else if(index==3&&reader.peek()==JsonToken.BEGIN_OBJECT){
                reader.beginObject();while(reader.hasNext()){
                    if("client_id".equals(reader.nextName())&&reader.peek()==JsonToken.STRING)found=reader.nextString();
                    else reader.skipValue();
                }reader.endObject();
                if(client.equals(found)&&!id.isEmpty())return id;
            }else reader.skipValue();
            index++;
        }reader.endArray();return client.equals(found)?id:"";
    }
}
