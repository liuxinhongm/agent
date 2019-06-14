package com.daxiang.android;

import com.android.ddmlib.*;
import com.daxiang.android.stf.AdbKit;
import com.daxiang.android.stf.Minicap;
import com.daxiang.android.stf.MinicapInstaller;
import com.daxiang.android.stf.Minitouch;
import com.daxiang.android.stf.MinitouchInstaller;
import com.daxiang.android.uiautomator.Uiautomator2Server;
import com.daxiang.android.uiautomator.Uiautomator2ServerApkInstaller;
import com.daxiang.api.MasterApi;
import com.daxiang.model.Device;
import com.daxiang.model.Platform;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.io.FileUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.File;
import java.util.Date;

/**
 * Created by jiangyitao.
 */
@Component
@Slf4j
public class AndroidDeviceChangeListener implements AndroidDebugBridge.IDeviceChangeListener {

    @Autowired
    private MasterApi masterApi;
    @Value("${server.address}")
    private String ip;
    @Value("${server.port}")
    private Integer port;

    @Override
    public void deviceConnected(IDevice device) {
        new Thread(() -> androidDeviceConnected(device)).start();
    }

    @Override
    public void deviceDisconnected(IDevice device) {
        new Thread(() -> androidDeviceDisconnected(device)).start();
    }

    @Override
    public void deviceChanged(IDevice device, int changeMask) {
        //ignore
    }

    /**
     * Android设备连接到电脑
     *
     * @param iDevice
     */
    private void androidDeviceConnected(IDevice iDevice) {
        String deviceId = iDevice.getSerialNumber();
        log.info("[{}]已连接", deviceId);

        log.info("[{}]等待手机上线", deviceId);
        AndroidUtils.waitForDeviceOnline(iDevice, 60);
        log.info("[{}]手机已上线", deviceId);

        AndroidDevice androidDevice = AndroidDeviceHolder.get(deviceId);
        if (androidDevice == null) {
            log.info("[{}]首次在agent上线", deviceId);
            log.info("[{}]检查是否已接入过master", deviceId);
            Device device = masterApi.getDeviceById(deviceId);
            if (device == null) {
                log.info("[{}]首次接入master", deviceId);
                androidDevice = initDevice(iDevice);
            } else {
                log.info("[{}]已接入过master", deviceId);
                androidDevice = new AndroidDevice(device, iDevice);
            }

            androidDevice.setMinicap(new Minicap(androidDevice));
            androidDevice.setMinitouch(new Minitouch(androidDevice));
            androidDevice.setAdbKit(new AdbKit(androidDevice));
            androidDevice.setUiautomator2Server(new Uiautomator2Server(androidDevice));

            AndroidDeviceHolder.add(deviceId, androidDevice);
        } else {
            log.info("[{}]非首次在agent上线", deviceId);
        }

        Device device = androidDevice.getDevice();
        device.setAgentIp(ip);
        device.setAgentPort(port);
        device.setStatus(Device.IDLE_STATUS);
        device.setLastOnlineTime(new Date());

        masterApi.saveDevice(device);
        log.info("[{}]deviceConnected处理完成", deviceId);
    }

    /**
     * Android设备断开电脑
     *
     * @param iDevice
     */
    public void androidDeviceDisconnected(IDevice iDevice) {
        String deviceId = iDevice.getSerialNumber();
        log.info("[{}]断开连接", deviceId);

        AndroidDevice androidDevice = AndroidDeviceHolder.get(deviceId);
        if (androidDevice == null) {
            return;
        }

        Device device = androidDevice.getDevice();
        device.setStatus(Device.OFFLINE_STATUS);
        device.setLastOfflineTime(new Date());

        masterApi.saveDevice(device);
        log.info("[{}]deviceDisconnected处理完成", deviceId);
    }

    /**
     * 首次接入系统，初始化Device
     *
     * @param iDevice
     * @return
     */
    private AndroidDevice initDevice(IDevice iDevice) {
        Device device = new Device();

        device.setPlatform(Platform.ANDROID);
        device.setCreateTime(new Date());
        device.setId(iDevice.getSerialNumber());
        try {
            device.setCpuInfo(AndroidUtils.getCpuInfo(iDevice));
        } catch (Exception e) {
            log.error("获取cpu信息失败", e);
            device.setCpuInfo("获取cpu信息失败");
        }
        try {
            device.setMemSize(AndroidUtils.getMemSize(iDevice));
        } catch (Exception e) {
            log.error("获取内存大小失败", e);
            device.setMemSize("获取内存大小失败");
        }
        device.setName(AndroidUtils.getDeviceName(iDevice));
        device.setSystemVersion(AndroidUtils.getAndroidVersion(iDevice));

        String[] resolution;
        try {
            resolution = AndroidUtils.getResolution(iDevice).split("x");
        } catch (Exception e) {
            throw new RuntimeException("获取屏幕分辨率失败", e);
        }
        device.setScreenWidth(Integer.parseInt(resolution[0]));
        device.setScreenHeight(Integer.parseInt(resolution[1]));

        // 截图并上传到服务器
        File screenshot = null;
        try {
            screenshot = AndroidUtils.screenshot(iDevice);
            String downloadURL = masterApi.uploadFile(screenshot);
            device.setImgUrl(downloadURL);
        } catch (Exception e) {
            log.error("设置首次接入master屏幕截图失败", e);
        } finally {
            // 删除截图
            FileUtils.deleteQuietly(screenshot);
        }

        AndroidDevice androidDevice = new AndroidDevice(device, iDevice);

        // 安装minicap minitouch uiautomatorServerApk
        try {
            installMinicapAndMinitouchAndUiAutomatorServerApk(androidDevice);
        } catch (Exception e) {
            throw new RuntimeException("安装必要程序到手机出错", e);
        }

        return androidDevice;
    }

    /**
     * 安装minicap minitouch uiautomatorServerApk
     *
     * @param androidDevice
     */
    private void installMinicapAndMinitouchAndUiAutomatorServerApk(AndroidDevice androidDevice) throws Exception {
        String deviceId = androidDevice.getId();
        IDevice iDevice = androidDevice.getIDevice();

        log.info("[{}]开始安装minicap", deviceId);
        MinicapInstaller minicapInstaller = new MinicapInstaller(iDevice);
        minicapInstaller.install();
        log.info("[{}]安装minicap成功", deviceId);

        log.info("[{}]开始安装minitouch", deviceId);
        MinitouchInstaller minitouchInstaller = new MinitouchInstaller(iDevice);
        minitouchInstaller.install();
        log.info("[{}]安装minitouch成功", deviceId);

        log.info("[{}]开始安装UiautomatorServerApk", deviceId);
        Uiautomator2ServerApkInstaller uiautomator2ServerApkInstaller = new Uiautomator2ServerApkInstaller(iDevice);
        uiautomator2ServerApkInstaller.install();
        log.info("[{}]安装UiautomatorServerApk成功", deviceId);
    }
}
