/*
 * Copyright (C) 2026 The Edith AOSP Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 */

// Real Discord Social SDK bridge. Rename this file to
// gamespace_discord_real.cpp, replace the stub in Android.bp, and add the
// SDK headers/library paths. See docs/gamespace-discord-rpc.md.

#define DISCORDPP_IMPLEMENTATION
#include "discordpp.h"

#include <jni.h>
#include <android/log.h>
#include <atomic>
#include <chrono>
#include <cstdlib>
#include <memory>
#include <string>
#include <thread>

#define TAG "GameSpaceDiscord"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

namespace {

std::shared_ptr<discordpp::Client> gClient;
std::thread gCallbackThread;
std::atomic<bool> gRunning{false};

std::string toStdString(JNIEnv *env, jstring value) {
    if (value == nullptr) return {};
    const char *chars = env->GetStringUTFChars(value, nullptr);
    std::string result(chars == nullptr ? "" : chars);
    if (chars != nullptr) env->ReleaseStringUTFChars(value, chars);
    return result;
}

void startCallbacks() {
    if (gRunning.exchange(true)) return;
    gCallbackThread = std::thread([] {
        while (gRunning.load()) {
            discordpp::RunCallbacks();
            std::this_thread::sleep_for(std::chrono::milliseconds(10));
        }
    });
}

}  // namespace

extern "C" JNIEXPORT jboolean JNICALL
Java_io_chaldeaprjkt_gamespace_discord_DiscordNative_nativeAvailable(
        JNIEnv *, jobject) {
    return JNI_TRUE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_io_chaldeaprjkt_gamespace_discord_DiscordNative_nativeInit(
        JNIEnv *env, jobject, jstring appId) {
    if (gClient) return JNI_TRUE;

    const std::string id = toStdString(env, appId);
    char *end = nullptr;
    const unsigned long long parsed = std::strtoull(id.c_str(), &end, 10);
    if (parsed == 0 || end == id.c_str()) {
        LOGE("Invalid Discord application id");
        return JNI_FALSE;
    }

    gClient = std::make_shared<discordpp::Client>();
    gClient->SetApplicationId(static_cast<uint64_t>(parsed));
    startCallbacks();
    LOGI("Discord client initialized for application %llu", parsed);
    return JNI_TRUE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_io_chaldeaprjkt_gamespace_discord_DiscordNative_nativeSetPresence(
        JNIEnv *env, jobject, jstring presenceName, jstring gameName, jstring state,
        jstring iconUrl, jlong startEpoch) {
    if (!gClient) return JNI_FALSE;

    const std::string activityName = toStdString(env, presenceName);
    const std::string name = toStdString(env, gameName);
    const std::string stateText = toStdString(env, state);
    const std::string icon = toStdString(env, iconUrl);

    discordpp::Activity activity;
    activity.SetType(discordpp::ActivityTypes::Playing);
    if (!activityName.empty()) {
        activity.SetName(activityName);
    }
    activity.SetDetails(name);
    activity.SetState(stateText);
    activity.SetStatusDisplayType(discordpp::StatusDisplayTypes::Details);

    discordpp::ActivityTimestamps timestamps;
    timestamps.SetStart(static_cast<int64_t>(startEpoch));
    activity.SetTimestamps(timestamps);

    if (!icon.empty()) {
        discordpp::ActivityAssets assets;
        assets.SetLargeImage(icon);
        assets.SetLargeText(name);
        activity.SetAssets(assets);
    }

    gClient->UpdateRichPresence(
            activity, [](const discordpp::ClientResult &result) {
                if (!result.Successful()) {
                    LOGE("UpdateRichPresence failed");
                }
            });
    return JNI_TRUE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_io_chaldeaprjkt_gamespace_discord_DiscordNative_nativeClearPresence(
        JNIEnv *, jobject) {
    if (!gClient) return JNI_FALSE;
    gClient->ClearRichPresence();
    return JNI_TRUE;
}

extern "C" JNIEXPORT void JNICALL
Java_io_chaldeaprjkt_gamespace_discord_DiscordNative_nativeReset(
        JNIEnv *, jobject) {
    gRunning.store(false);
    if (gCallbackThread.joinable()) {
        gCallbackThread.join();
    }
    if (gClient) {
        gClient->ClearRichPresence();
        gClient.reset();
    }
    LOGI("Discord client reset");
}
