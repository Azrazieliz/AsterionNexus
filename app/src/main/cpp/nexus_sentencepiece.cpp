#include <jni.h>
#include <string>
#include <vector>
#include "sentencepiece_processor.h"

static std::string bytes(JNIEnv* env,jbyteArray a){
    if(!a)return {};
    std::string out(env->GetArrayLength(a),'\0');
    if(!out.empty())env->GetByteArrayRegion(a,0,out.size(),reinterpret_cast<jbyte*>(&out[0]));
    return out;
}
static void fail(JNIEnv* env,const char* message){
    jclass c=env->FindClass("java/io/IOException");
    if(c)env->ThrowNew(c,message);
}
extern "C" JNIEXPORT jlong JNICALL
Java_com_asterion_nexus_NexusSentencePiece_open(JNIEnv* env,jobject,jbyteArray path){
    auto* p=new sentencepiece::SentencePieceProcessor();
    auto status=p->Load(bytes(env,path));
    if(!status.ok()){delete p;fail(env,"SentencePiece model load failed");return 0;}
    return reinterpret_cast<jlong>(p);
}
extern "C" JNIEXPORT jobjectArray JNICALL
Java_com_asterion_nexus_NexusSentencePiece_encode(JNIEnv* env,jobject,jlong handle,jbyteArray input){
    auto* p=reinterpret_cast<sentencepiece::SentencePieceProcessor*>(handle);
    if(!p){fail(env,"Tokenizer closed");return nullptr;}
    std::vector<std::string> pieces;
    auto status=p->Encode(bytes(env,input),&pieces);
    if(!status.ok()||pieces.size()>8192){fail(env,"SentencePiece encoding failed or exceeded budget");return nullptr;}
    jclass arrayClass=env->FindClass("[B");
    jobjectArray output=env->NewObjectArray(pieces.size(),arrayClass,nullptr);
    for(size_t i=0;i<pieces.size();i++){
        auto a=env->NewByteArray(pieces[i].size());
        env->SetByteArrayRegion(a,0,pieces[i].size(),reinterpret_cast<const jbyte*>(pieces[i].data()));
        env->SetObjectArrayElement(output,i,a);env->DeleteLocalRef(a);
    }
    return output;
}
extern "C" JNIEXPORT jbyteArray JNICALL
Java_com_asterion_nexus_NexusSentencePiece_decode(JNIEnv* env,jobject,jlong handle,jobjectArray input){
    auto* p=reinterpret_cast<sentencepiece::SentencePieceProcessor*>(handle);
    if(!p){fail(env,"Tokenizer closed");return nullptr;}
    std::vector<std::string> pieces;
    for(jsize i=0;i<env->GetArrayLength(input);i++){
        auto a=static_cast<jbyteArray>(env->GetObjectArrayElement(input,i));
        pieces.push_back(bytes(env,a));env->DeleteLocalRef(a);
    }
    std::string output;
    if(!p->Decode(pieces,&output).ok()){fail(env,"SentencePiece decoding failed");return nullptr;}
    auto a=env->NewByteArray(output.size());
    env->SetByteArrayRegion(a,0,output.size(),reinterpret_cast<const jbyte*>(output.data()));
    return a;
}
extern "C" JNIEXPORT void JNICALL
Java_com_asterion_nexus_NexusSentencePiece_close(JNIEnv*,jobject,jlong handle){
    delete reinterpret_cast<sentencepiece::SentencePieceProcessor*>(handle);
}
