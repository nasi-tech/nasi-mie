package me.qfdk.nasimie.tools;

import me.qfdk.nasimie.entity.User;
import org.springframework.stereotype.Component;

import java.io.UnsupportedEncodingException;
import java.util.Base64;
import java.util.Map;

@Component
public class Tools {

    public static String getSSRUrl(String host, String port, String password, String remark) throws UnsupportedEncodingException {
        String pass = new String(Base64.getEncoder().encode(password.getBytes("UTF-8"))).replace("=", "");
        //ssr://base64(host:port:protocol:method:obfs:base64pass
        String newRemark = new String(Base64.getEncoder().encode(remark.getBytes("UTF-8")));
        String tmp = host + ":" + port + ":origin:rc4-md5:plain:" + pass + "/?remarks=" + newRemark;
        return new String(Base64.getEncoder().encode(tmp.getBytes())).replace("=", "");
    }

    public static User updateInfo(User user, Map<String, String> info) throws UnsupportedEncodingException {
//        String host = client.getInstances(user.getContainerLocation()).get(0).getHost();
        user.setContainerId(info.get("containerId"));
        user.setContainerStatus(info.get("status"));
        user.setContainerPort(info.get("port"));
        String pontLocation = user.getPontLocation();
        if (pontLocation.trim().equals("non")) {
            user.setQrCode(Tools.getSSRUrl(user.getContainerLocation() + ".qfdk.me", info.get("port"), info.get("pass"), user.getContainerLocation()));
        } else {
            user.setQrCode(Tools.getSSRUrl(pontLocation + ".qfdk.me", info.get("port"), info.get("pass"), pontLocation.split("nasi-campur-")[1] + " => " + user.getContainerLocation().split("nasi-campur-")[1]));
        }
        return user;
    }

    // 加密方法
    public static String getPass(String str) {
        return new StringBuffer(str).reverse().toString();
    }
}
