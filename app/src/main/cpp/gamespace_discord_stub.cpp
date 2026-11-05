/*
 * Copyright (C) 2026 The Edith AOSP Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 */

#include <jni.h>
#include <android/log.h>

#define TAG "GameSpaceDiscord"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)

extern "C" JNIEXPORT jboolean JNICALL
Java_io_chaldeaprjkt_gamespace_discord_DiscordNative_nativeAvailable(
        JNIEnv *, jobject) {
    return JNI_FALSE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_io_chaldeaprjkt_gamespace_discord_DiscordNative_nativeInit(
        JNIEnv *, jobject, jstring) {
    LOGI("Discord Social SDK is not bundled; Rich Presence disabled");
    return JNI_FALSE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_io_chaldeaprjkt_gamespace_discord_DiscordNative_nativeSetPresence(
        JNIEnv *, jobject, jint, jstring, jstring, jstring, jstring, jstring,
        jstring, jstring, jlong) {
    return JNI_FALSE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_io_chaldeaprjkt_gamespace_discord_DiscordNative_nativeClearPresence(
        JNIEnv *, jobject) {
    return JNI_FALSE;
}

extern "C" JNIEXPORT void JNICALL
Java_io_chaldeaprjkt_gamespace_discord_DiscordNative_nativeReset(
        JNIEnv *, jobject) {
}
