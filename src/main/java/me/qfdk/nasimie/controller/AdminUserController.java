package me.qfdk.nasimie.controller;

import lombok.extern.slf4j.Slf4j;
import me.qfdk.nasimie.entity.User;
import me.qfdk.nasimie.repository.UserRepository;
import me.qfdk.nasimie.service.ContainerService;
import me.qfdk.nasimie.tools.Tools;
import org.apache.commons.lang.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.propertyeditors.CustomDateEditor;
import org.springframework.cloud.client.discovery.DiscoveryClient;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.logout.SecurityContextLogoutHandler;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.WebDataBinder;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.RestTemplate;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.UnsupportedEncodingException;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.stream.Collectors;

@Controller
@Slf4j
public class AdminUserController {
    @Autowired
    RestTemplate restTemplate;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private DiscoveryClient client;

    @Autowired
    private ContainerService containerService;

    @Value("${nasiCampurSsh.sshPassword}")
    private String sshPassword;

    private int currentPage;

    @InitBinder
    protected void initBinder(WebDataBinder binder) {
        SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd");
        binder.registerCustomEditor(Date.class, new CustomDateEditor(dateFormat, true));
    }

    @GetMapping("/")
    public String index(Model model) {
        List<Map<String, String>> listServer = new ArrayList<>();
        initServersList(listServer);
        model.addAttribute("listServer", listServer);
        return "index";
    }

    private void initServersList(List<Map<String, String>> listServer) {
        getLocations(false)
                .keySet().stream()
                .filter(s -> !s.contains("nasi-campur-cn")).collect(Collectors.toList()).forEach(location -> {
            Map<String, String> map = new HashMap<>();
            map.put("name", location);
            map.put("url", "http://" + location + ".qfdk.me:8762/");
            listServer.add(map);

        });
    }

    @GetMapping("/help")
    public String help() {
        return "help";
    }

    @GetMapping("/login")
    public String login() {
        return "login";
    }

    @RequestMapping(value = "/logout", method = RequestMethod.GET)
    public String logoutPage(HttpServletRequest request, HttpServletResponse response) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null) {
            new SecurityContextLogoutHandler().logout(request, response, auth);
        }
        return "redirect:/login?logout";
    }

    @GetMapping("/admin")
    public String show(Authentication authentication, Model model, @RequestParam(defaultValue = "0") int page) {
        model.addAttribute("users", userRepository.findAll(PageRequest.of(page, 10)));
        model.addAttribute("currentPage", page);
        model.addAttribute("paidUsersCount", userRepository.findUserByIconNotLike("%label-warning%").size());
        model.addAttribute("totalUsersCount", userRepository.findAll().size());
        model.addAttribute("locations", getLocations(true));
        this.currentPage = page;
        return "admin";
    }

    @GetMapping("/getLocations")
    @ResponseBody
    public Map<String, Integer> getLocations() {
        return getLocations(true);
    }

    @GetMapping("/getSSHPassword")
    @ResponseBody
    public String getSSHPassword() {
        return sshPassword;
    }

    @PostMapping("/save")
    public String save(User user) {
        // 新用户
        if (user.getId() == null || StringUtils.isEmpty(user.getContainerId())) {
            log.info("[新建容器]-> " + user.getWechatName() + " => " + user.getContainerLocation());
            try {
                Map info = restTemplate
                        .getForEntity("http://" + user.getContainerLocation() + "/createContainer?wechatName=" + user.getWechatName(), Map.class)
                        .getBody();
                Tools.updateInfo(user, info);
                if (!user.getPontLocation().equals("non")) {
                    log.info("[添加端口转发] {} -> {}", user.getPontLocation(), user.getContainerPort());
                    restTemplate.getForEntity(
                            "http://" + user.getPontLocation() + "/addPont?host=" + user.getContainerLocation() + ".qfdk.me&port=" + user
                                    .getContainerPort() + "&sshUser=root" + "&sshPassword=" + sshPassword, String.class);
                }
            } catch (Exception e) {
                log.error("[添加端口转发]{}", e.getMessage());
            }
        } else {
            // 老用户
            User oldUser = userRepository.findById(user.getId()).get();
            //检查是否换机房
            if (!oldUser.getContainerLocation().equals(user.getContainerLocation())) {
                log.info("[换机房] [" + user.getWechatName() + "] " + oldUser.getContainerLocation() + " --> " + user.getContainerLocation());

                try {
                    // 删除转发
                    deleteProxyPort(user);
                    // 建立新容器
                    Map info = restTemplate.getForEntity(
                            "http://" + user.getContainerLocation() + "/createContainer?wechatName=" + user.getWechatName() + "&port=" + oldUser
                                    .getContainerPort(), Map.class).getBody();
                    log.info(user.getContainerLocation() + " ] 建立容器 -> " + info);
                    Tools.updateInfo(user, info);

                    // 删除旧容器
                    deleteContainerByContainerId(oldUser.getContainerId(), true);
                } catch (Exception e) {
                    log.error("[换机房失败]{}", e.toString());
                }
            }

        }
        userRepository.save(user);
        return "redirect:/admin?page=" + this.currentPage;
    }

    private void deleteProxyPort(User user) {
        //判断是否有端口转发
        if (!user.getPontLocation().trim().equals("non")) {
            try {
                log.info("[删除端口转发] {} -> {}", user.getPontLocation(), user.getContainerPort());
                restTemplate.getForEntity("http://" + user.getPontLocation() + "/deletePont?port=" + user.getContainerPort(), String.class);
                user.setPontLocation("non");
            } catch (Exception e) {
                log.error("[删除端口转发] {}", e.getMessage());
                e.printStackTrace();
            }
        }
    }

    private void deleteContainerByContainerId(String containerId, Boolean deleteContainerLocation) {
        User user = userRepository.findByContainerId(containerId);
        // 删除端口转发
        deleteProxyPort(user);
        restTemplate.getForEntity("http://" + user.getContainerLocation() + "/deleteContainer?id=" + containerId, Integer.class);
        user.setContainerStatus("");
        user.setContainerId("");
        if (deleteContainerLocation) {
            user.setContainerPort("");
            user.setContainerLocation("");
        }
        user.setQrCode("");
        userRepository.save(user);
        log.info("[删除旧容器] OK");
    }

    @GetMapping("/deleteContainer")
    public String deleteContainer(@RequestParam("id") String containerId, @RequestParam("role") String role) {
        deleteContainerByContainerId(containerId, true);
        if (role.equals("admin")) {
            return "redirect:/admin?page=" + this.currentPage;
        }
        return "redirect:/user/findUserByWechatName?wechatName=" + role;
    }

    @GetMapping("/startContainer")
    public String startContainer(@RequestParam("id") String containerId, @RequestParam("role") String role) {
        User user = userRepository.findByContainerId(containerId);
        String status = restTemplate.getForEntity("http://" + user.getContainerLocation() + "/startContainer?id=" + containerId, String.class)
                .getBody();
        user.setContainerStatus(status);
        userRepository.save(user);
        if (role.equals("admin")) {
            return "redirect:/admin?page=" + this.currentPage;
        }
        return "redirect:/user/findUserByWechatName?wechatName=" + role;
    }

    @GetMapping("/reCreateContainer")
    public String reCreateContainer(@RequestParam("id") String containerId, @RequestParam("role") String role) {
        User user = userRepository.findByContainerId(containerId);
        containerService.reCreateContainerByUser(user, restTemplate);
        log.info("[User][reCreateContainer] {} 重建容器(二维码) 成功", user.getWechatName());
        if (role.equals("admin")) {
            return "redirect:/admin?page=" + this.currentPage;
        }
        return "redirect:/user/findUserByWechatName?wechatName=" + role;
    }

    @GetMapping("/restartContainer")
    public String restartContainer(@RequestParam("id") String containerId, @RequestParam("role") String role) {
        User user = userRepository.findByContainerId(containerId);
        String status = restTemplate.getForEntity("http://" + user.getContainerLocation() + "/restartContainer?id=" + containerId, String.class)
                .getBody();
        user.setContainerStatus(status);
        userRepository.save(user);
        if (role.equals("admin")) {
            return "redirect:/admin?page=" + this.currentPage;
        }
        return "redirect:/user/findUserByWechatName?wechatName=" + role;
    }

    @GetMapping("/stopContainer")
    public String stopContainer(@RequestParam("id") String containerId, @RequestParam("role") String role) {
        stopContainerByContainerId(containerId);
        if (role.equals("admin")) {
            return "redirect:/admin?page=" + this.currentPage;
        }
        return "redirect:/user/findUserByWechatName?wechatName=" + role;
    }

    private void stopContainerByContainerId(String containerId) {
        User user = userRepository.findByContainerId(containerId);
        String status = restTemplate.getForEntity("http://" + user.getContainerLocation() + "/stopContainer?id=" + containerId, String.class)
                .getBody();
        user.setContainerStatus(status);
        userRepository.save(user);
    }

    @GetMapping("/delete")
    public String delete(Integer id, @RequestParam("role") String role) {
        User user = userRepository.findById(id).get();
        // 删除端口转发
        if (null != user.getPontLocation()) {
            deleteProxyPort(user);
        }
        String containerId = user.getContainerId();
        if (!StringUtils.isEmpty(user.getContainerLocation())) {
            restTemplate.getForEntity("http://" + user.getContainerLocation() + "/deleteContainer?id=" + containerId, Integer.class).getBody();
        }
        userRepository.deleteById(id);
        if (role.equals("admin")) {
            return "redirect:/admin?page=" + this.currentPage;
        }
        return "redirect:/findUserByWechatName?wechatName=" + role;
    }

    @GetMapping("/findUserById")
    @ResponseBody
    public User findUserById(@RequestParam("id") Integer id) {
        return userRepository.findById(id).get();
    }

    private Map<String, Integer> getLocations(boolean withCount) {
        List<String> services = client.getServices();
        Map<String, Integer> available_Services = new HashMap<>();
        for (String service : services) {
            if (service.contains("campur")) {
                try {
                    int nb = 0;
                    if (withCount) {
                        nb = userRepository.countByContainerLocation(service);
                    }
                    available_Services.put(service, nb);
                } catch (Exception e) {
                    log.error(service + " --> 无响应");
                }
            }
        }
        return available_Services;
    }

    @GetMapping("/refreshNetwork")
    public String refreshNetwork(@RequestParam("instanceLocation") String instanceLocation) {
        List<User> listUsers = userRepository.findAll();
        if (instanceLocation.equals("all")) {
            listUsers.stream().forEach(user -> {
                containerService.refreshUserNetwork(user, restTemplate, log);
            });
        } else {
            listUsers.stream().filter(instance ->
                    instance.getContainerLocation().equals(instanceLocation)
            ).forEach(user -> {
                containerService.refreshUserNetwork(user, restTemplate, log);
            });
        }
        log.info("-----------------------------------------");
        return "redirect:/admin?page=" + this.currentPage;
    }

    @GetMapping("/reCreateAllContainers")
    public String reCreateAllContainers() {
        List<User> listUsers = userRepository.findAll();
        log.info("[Admin][containers] all containers : " + listUsers.size());
        listUsers.forEach(user -> {
            String containerId = user.getContainerId();
            log.warn("[User](" + user.getWechatName() + ") will destroy :" + containerId);
            try {
                stopContainerByContainerId(containerId);
                user.setContainerStatus("exited");
                log.warn("[User][STOP](OK)(" + user.getWechatName() + ") container was [stopped] :" + containerId);
            } catch (Exception e) {
                log.error("[STOP](KO)-> " + containerId + ": container not found！");
                log.error(e.getMessage());
            }
            try {
                deleteContainerByContainerId(user.getContainerId(), false);
                log.warn("[User][DEL](OK)(" + user.getWechatName() + ") container was [deleted] :" + containerId);
            } catch (Exception e) {
                log.error("[DEL](KO)-> " + containerId + ": container not found！");
                log.error(e.getMessage());
            }

        });
        listUsers.forEach(user -> containerService.reCreateContainer(user, restTemplate));
        log.info("[Admin][Containers](OK) was created.");
        return "redirect:/admin?page=" + this.currentPage;
    }

    @GetMapping("/updateQrCode")
    public String updateQrCode() {
        List<User> listUsers = userRepository.findAll();
        listUsers.forEach(user -> {
            try {
                user.setQrCode(Tools.getSSRUrl(user.getContainerLocation() + ".qfdk.me", user.getContainerPort(), Tools.getPass(user.getWechatName()),
                        user.getContainerLocation()));
                userRepository.save(user);
                log.info("[Admin][updateQrCode](OK)  updateQrCode for " + user.getWechatName() + " was succeed.");
            } catch (UnsupportedEncodingException e) {
                log.error("[Admin][updateQrCode](KO)  updateQrCode.");
                log.error(e.toString());

            }
        });
        return "redirect:/admin?page=" + this.currentPage;
    }

    @GetMapping("/cleanNetworks")
    public String cleanNetworks() {
        List<User> listUsers = userRepository.findAll();
        listUsers.forEach(user -> {
            try {
                user.setNetworkRx(0);
                user.setNetworkTx(0);
                userRepository.save(user);
                log.info("[Admin][cleanNetworks](OK)  cleanNetworks for " + user.getWechatName() + " was succeed.");
            } catch (Exception e) {
                log.error("[Admin][cleanNetworks](KO)  cleanNetworks.");
                log.error(e.toString());
            }
        });
        return "redirect:/admin?page=" + this.currentPage;
    }

}
