/*
 * Copyright (C) 2026 The Edith AOSP Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 */

// Discord Social SDK bridge: owns the native client, drives its callback loop and forwards the
// presence results and the RPC status to Kotlin. See docs/gamespace-discord-rpc.md.

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
JavaVM* gVm = nullptr;
jobject gResultListener = nullptr;
jmethodID gResultMethod = nullptr;
jmethodID gStatusMethod = nullptr;
// Last status handed to Kotlin. The SDK can report the same value repeatedly and the callback
// fires from the SDK's own thread, so it is compared before a Java call is made.
std::atomic<int> gLastStatus{-1};

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

// Delivers the SDK's verdict on a presence update to Kotlin. A true return from
// nativeSetPresence only means queued; this callback is the only success signal.
void reportResult(bool success, const std::string &error) {
    JavaVM *vm = gVm;
    jobject listener = gResultListener;
    jmethodID method = gResultMethod;
    if (vm == nullptr || listener == nullptr || method == nullptr) return;

    JNIEnv *env = nullptr;
    bool attached = false;
    const jint state = vm->GetEnv(reinterpret_cast<void **>(&env), JNI_VERSION_1_6);
    if (state == JNI_EDETACHED) {
        if (vm->AttachCurrentThread(&env, nullptr) != JNI_OK) return;
        attached = true;
    } else if (state != JNI_OK) {
        return;
    }

    jstring message = env->NewStringUTF(error.c_str());
    env->CallVoidMethod(listener, method, success ? JNI_TRUE : JNI_FALSE, message);
    if (env->ExceptionCheck()) env->ExceptionClear();
    if (message != nullptr) env->DeleteLocalRef(message);
    if (attached) vm->DetachCurrentThread();
}

// Forwards the SDK's status changes. An unchanged status is not forwarded.
void reportStatus(discordpp::Client::Status status, int detail) {
    const int value = status == discordpp::Client::Status::Ready ? 3
                    : status == discordpp::Client::Status::Connected ? 2
                    : static_cast<int>(status);
    if (gLastStatus.exchange(value) == value) return;

    JavaVM *vm = gVm;
    jobject listener = gResultListener;
    jmethodID method = gStatusMethod;
    if (vm == nullptr || listener == nullptr || method == nullptr) return;

    JNIEnv *env = nullptr;
    bool attached = false;
    const jint state = vm->GetEnv(reinterpret_cast<void **>(&env), JNI_VERSION_1_6);
    if (state == JNI_EDETACHED) {
        if (vm->AttachCurrentThread(&env, nullptr) != JNI_OK) return;
        attached = true;
    } else if (state != JNI_OK) {
        return;
    }

    env->CallVoidMethod(listener, method, static_cast<jint>(value), static_cast<jint>(detail));
    if (env->ExceptionCheck()) env->ExceptionClear();
    if (attached) vm->DetachCurrentThread();
}

}  // namespace

extern "C" JNIEXPORT jboolean JNICALL
Java_io_chaldeaprjkt_gamespace_discord_DiscordNative_nativeAvailable(
        JNIEnv *, jobject) {
    return JNI_TRUE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_io_chaldeaprjkt_gamespace_discord_DiscordNative_nativeInit(
        JNIEnv *env, jobject, jstring appId, jobject listener) {
    env->GetJavaVM(&gVm);
    if (gResultListener != nullptr) {
        env->DeleteGlobalRef(gResultListener);
        gResultListener = nullptr;
        gResultMethod = nullptr;
        gStatusMethod = nullptr;
    }
    if (listener != nullptr) {
        gResultListener = env->NewGlobalRef(listener);
        jclass listenerClass = env->GetObjectClass(listener);
        gResultMethod = env->GetMethodID(listenerClass, "onResult",
                                         "(ZLjava/lang/String;)V");
        if (gResultMethod == nullptr || env->ExceptionCheck()) {
            // A missing keep rule surfaces here; clear the pending exception so it cannot escape
            // into the caller.
            env->ExceptionClear();
            gResultMethod = nullptr;
            LOGE("ResultListener.onResult not found; results will not be reported");
        }
        gStatusMethod = env->GetMethodID(listenerClass, "onStatus", "(II)V");
        if (gStatusMethod == nullptr || env->ExceptionCheck()) {
            env->ExceptionClear();
            gStatusMethod = nullptr;
            LOGE("ResultListener.onStatus not found; status changes will not be reported");
        }
        env->DeleteLocalRef(listenerClass);
    }
    if (gClient) return JNI_TRUE;

    const std::string id = toStdString(env, appId);
    char *end = nullptr;
    const unsigned long long parsed = std::strtoull(id.c_str(), &end, 10);
    if (parsed == 0 || end == id.c_str()) {
        LOGE("Invalid Discord application id");
        return JNI_FALSE;
    }

    gClient = std::make_shared<discordpp::Client>();
    // The SDK logs RPC failures, bind errors included, only on its own logger; the result
    // callbacks carry no detail.
    gClient->AddLogCallback(
            [](std::string message, discordpp::LoggingSeverity severity) {
                if (severity >= discordpp::LoggingSeverity::Warning) {
                    LOGE("SDK: %s", message.c_str());
                } else {
                    LOGI("SDK: %s", message.c_str());
                }
            },
            discordpp::LoggingSeverity::Info);
    gClient->SetApplicationId(static_cast<uint64_t>(parsed));
    // The abnormal RPC close is reported only here.
    gClient->SetStatusChangedCallback(
            [](discordpp::Client::Status status, discordpp::Client::Error error,
               int32_t errorDetail) {
                const int value = status == discordpp::Client::Status::Ready ? 3
                                : status == discordpp::Client::Status::Connected ? 2
                                : static_cast<int>(status);
                LOGI("Discord status %d (error=%d detail=%d)", value,
                     static_cast<int>(error), errorDetail);
                reportStatus(status, errorDetail);
            });
    // No Connect(): it opens the social gateway, which needs an authenticated user and tears the
    // RPC connection down without one. SetApplicationId starts the RPC manager by itself.
    startCallbacks();
    LOGI("Discord client initialized for application %llu", parsed);
    return JNI_TRUE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_io_chaldeaprjkt_gamespace_discord_DiscordNative_nativeSetPresence(
        JNIEnv *env, jobject, jint type, jstring activityNameValue, jstring detailsValue,
        jstring stateValue, jstring largeImageValue, jstring largeTextValue,
        jstring smallImageValue, jstring smallTextValue, jlong startEpoch) {
    if (!gClient) return JNI_FALSE;

    const std::string activityName = toStdString(env, activityNameValue);
    const std::string details = toStdString(env, detailsValue);
    const std::string stateText = toStdString(env, stateValue);
    const std::string largeImage = toStdString(env, largeImageValue);
    const std::string largeText = toStdString(env, largeTextValue);
    const std::string smallImage = toStdString(env, smallImageValue);
    const std::string smallText = toStdString(env, smallTextValue);

    discordpp::Activity activity;
    // Listening renders the "Listening to <player>" header; older clients are handled by the SDK.
    activity.SetType(
            type == 2 ? discordpp::ActivityTypes::Listening
                      : discordpp::ActivityTypes::Playing);
    // The name is the header line the card renders ("Playing <name>" / "Listening to <name>"),
    // so both types carry it.
    if (!activityName.empty()) {
        activity.SetName(activityName);
    }
    // Details and state must be 2-128 characters when present; an empty value invalidates the
    // whole update.
    if (details.size() >= 2) {
        activity.SetDetails(details);
    }
    if (stateText.size() >= 2) {
        activity.SetState(stateText);
    }
    // StatusDisplayType is deliberately left unset: GameSpace does not ask Discord to put any
    // of the activity fields in the compact status line.

    discordpp::ActivityTimestamps timestamps;
    timestamps.SetStart(static_cast<int64_t>(startEpoch));
    activity.SetTimestamps(timestamps);

    if (!largeImage.empty() || !largeText.empty() || !smallImage.empty() ||
            !smallText.empty()) {
        discordpp::ActivityAssets assets;
        if (!largeImage.empty()) assets.SetLargeImage(largeImage);
        if (!largeText.empty()) assets.SetLargeText(largeText);
        // The small badge over the artwork, with its tooltip. The caller only sets it when the
        // large image has artwork of its own.
        if (!smallImage.empty()) assets.SetSmallImage(smallImage);
        if (!smallText.empty()) assets.SetSmallText(smallText);
        activity.SetAssets(assets);
    }

    gClient->UpdateRichPresence(
            activity, [](const discordpp::ClientResult &result) {
                if (!result.Successful()) {
                    LOGE("UpdateRichPresence failed: %s", result.Error().c_str());
                    reportResult(false, result.Error());
                } else {
                    LOGI("Rich presence updated");
                    reportResult(true, {});
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

// Synchronous view of the RPC bridge. An update queued while the client is not Ready is dropped
// without a callback, so callers gate on this value.
extern "C" JNIEXPORT jint JNICALL
Java_io_chaldeaprjkt_gamespace_discord_DiscordNative_nativeStatus(JNIEnv *, jobject) {
    if (!gClient) return -1;
    return static_cast<jint>(gClient->GetStatus());
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
    gLastStatus.store(-1);
    LOGI("Discord client reset");
}
