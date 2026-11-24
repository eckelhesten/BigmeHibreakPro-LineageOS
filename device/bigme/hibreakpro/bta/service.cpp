/*
 * Copyright (C) 2026 The LineageOS Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

// System-side Bluetooth audio (see Android.bp): the AOSP Bluetooth audio provider and a HIDL
// audio HAL factory for the AOSP A2DP module, in one process so that both use the same
// BluetoothAudioSession instances.

#undef LOG_TAG  // the force-included VersionMacro.h already pulled in log.h
#define LOG_TAG "HiBreakBtAudio"

#include <signal.h>

#include <string>

#include <android/binder_manager.h>
#include <android/binder_process.h>
#include <hidl/HidlTransportSupport.h>
#include <log/log.h>

#include "BluetoothAudioProviderFactory.h"
#include "core/default/DevicesFactory.h"

using ::aidl::android::hardware::bluetooth::audio::BluetoothAudioProviderFactory;
using ::android::OK;
using ::android::sp;
using ::android::hardware::audio::CPP_VERSION::IDevicesFactory;
using ::android::hardware::audio::CPP_VERSION::implementation::DevicesFactory;

int main() {
    signal(SIGPIPE, SIG_IGN);
    ::android::hardware::configureRpcThreadpool(16, true /* callerWillJoin */);
    ABinderProcess_setThreadPoolMaxThreadCount(4);
    ABinderProcess_startThreadPool();

    // The provider first: the Bluetooth stack looks for it when it starts.
    auto provider = ::ndk::SharedRefBase::make<BluetoothAudioProviderFactory>();
    const std::string providerName =
            std::string() + BluetoothAudioProviderFactory::descriptor + "/default";
    if (AServiceManager_addService(provider->asBinder().get(), providerName.c_str()) !=
        STATUS_OK) {
        ALOGE("could not register %s", providerName.c_str());
        return 1;
    }

    // The module name is what AudioFlinger asks for ("sysbta" from our policy file); the factory
    // opens /system/lib64/hw/audio.<name>.default.so. The vendor's factory doesn't know it.
    sp<IDevicesFactory> factory = new DevicesFactory();
    if (factory->registerAsService("sysbta") != OK) {
        ALOGE("could not register IDevicesFactory/sysbta");
        return 1;
    }
    ALOGI("Bluetooth audio provider and IDevicesFactory/sysbta registered");

    ::android::hardware::joinRpcThreadpool();
    return 1;
}
