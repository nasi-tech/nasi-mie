package me.qfdk.nasimie.controler;

import com.spotify.docker.client.exceptions.DockerException;
import me.qfdk.nasimie.entity.User;
import me.qfdk.nasimie.repository.UserRepository;
import me.qfdk.nasimie.tools.Tools;
import org.apache.commons.lang.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.propertyeditors.CustomDateEditor;
import org.springframework.cloud.client.ServiceInstance;
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

@Controller()
public class AdminUserControler {
    @Autowired
    RestTemplate restTemplate;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private DiscoveryClient client;

    @Autowired
    private Tools tools;

    private int currentPage;

    private Logger logger = LoggerFactory.getLogger(this.getClass());

    @InitBinder
    protected void initBinder(WebDataBinder binder) {
        SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd");
        binder.registerCustomEditor(Date.class, new CustomDateEditor(dateFormat, true));
    }

    @GetMapping("/")
    public String index(Model model) {
        Map<String, String> map = new HashMap<>();
        for (String location : getLocations()) {
            for (ServiceInstance instance : client.getInstances(location)) {
                map.put(location, instance.getHost());
            }
        }
        model.addAttribute("locationMap", map);

        return "index";
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
        model.addAttribute("locations", getLocations());
        model.addAttribute("users", userRepository.findAll(PageRequest.of(page, 10)));
        model.addAttribute("currentPage", page);
        model.addAttribute("paidUsersCount", userRepository.findUserByIconNotLike("%label-warning%").size());
        model.addAttribute("totalUsersCount", userRepository.findAll().size());
        this.currentPage = page;
        return "admin";
    }

    @PostMapping("/save")
    public String save(User user) {
        // 新用户
        if (user.getId() == null || StringUtils.isEmpty(user.getContainerId())) {
            System.err.println("[新建容器]-> " + user.getWechatName());
            try {
                Map<String, String> info = restTemplate.getForEntity("http://" + user.getContainerLocation() + "/createContainer?wechatName=" + user.getWechatName(), Map.class).getBody();
                updateInfo(user, info);
            } catch (Exception e) {
                e.printStackTrace();
            }

        } else {
            //检查是否换机房
            User oldUser = userRepository.findById(user.getId()).get();
            if (!oldUser.getContainerLocation().equals(user.getContainerLocation())) {
                System.err.println("[换机房] [" + user.getWechatName() + "] " + oldUser.getContainerLocation() + " --> " + user.getContainerLocation());

                try {
                    // 建立新容器
                    Map<String, String> info = restTemplate.getForEntity("http://" + user.getContainerLocation() + "/createContainer?wechatName=" + user.getWechatName() + "&port=" + oldUser.getContainerPort(), Map.class).getBody();
                    updateInfo(user, info);
                    // 删除旧容器
                    deleteContainerById(oldUser.getContainerId());
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
        }
        userRepository.save(user);
        return "redirect:/admin?page=" + this.currentPage;
    }

    private void updateInfo(User user, Map<String, String> info) throws UnsupportedEncodingException {
        String host = client.getInstances(user.getContainerLocation()).get(0).getHost();
        user.setContainerId(info.get("containerId"));
        user.setContainerStatus(info.get("status"));
        user.setContainerPort(info.get("port"));
        user.setQrCode(Tools.getSSRUrl(host, info.get("port"), info.get("pass"), user.getContainerLocation()));
    }


    private void deleteContainerById(String containerId) {
        User user = userRepository.findByContainerId(containerId);
        restTemplate.getForEntity("http://" + user.getContainerLocation() + "/deleteContainer?id=" + containerId, Integer.class);
        user.setContainerStatus("");
        user.setContainerId("");
        user.setContainerPort("");
        user.setContainerLocation("");
        user.setQrCode("");
        userRepository.save(user);
    }

    @GetMapping("/deleteContainer")
    public String deleteContainer(@RequestParam("id") String containerId, @RequestParam("role") String role) throws DockerException, InterruptedException {
        deleteContainerById(containerId);
        if (role.equals("admin")) {
            return "redirect:/admin?page=" + this.currentPage;
        }
        return "redirect:/user/findUserByWechatName?wechatName=" + role;
    }

    @GetMapping("/startContainer")
    public String startContainer(@RequestParam("id") String containerId, @RequestParam("role") String role) {
        User user = userRepository.findByContainerId(containerId);
        String status = restTemplate.getForEntity("http://" + user.getContainerLocation() + "/startContainer?id=" + containerId, String.class).getBody();
        user.setContainerStatus(status);
        userRepository.save(user);
        if (role.equals("admin")) {
            return "redirect:/admin?page=" + this.currentPage;
        }
        return "redirect:/user/findUserByWechatName?wechatName=" + role;
    }

    @GetMapping("/restartContainer")
    public String restartContainer(@RequestParam("id") String containerId, @RequestParam("role") String role) {
        User user = userRepository.findByContainerId(containerId);

        String status = restTemplate.getForEntity("http://" + user.getContainerLocation() + "/restartContainer?id=" + containerId, String.class).getBody();
        user.setContainerStatus(status);
        userRepository.save(user);
        if (role.equals("admin")) {
            return "redirect:/admin?page=" + this.currentPage;
        }
        return "redirect:/user/findUserByWechatName?wechatName=" + role;
    }

    @GetMapping("/stopContainer")
    public String stopContainer(@RequestParam("id") String containerId, @RequestParam("role") String role) {
        User user = userRepository.findByContainerId(containerId);
        String status = restTemplate.getForEntity("http://" + user.getContainerLocation() + "/stopContainer?id=" + containerId, String.class).getBody();
        user.setContainerStatus(status);
        userRepository.save(user);
        if (role.equals("admin")) {
            return "redirect:/admin?page=" + this.currentPage;
        }
        return "redirect:/user/findUserByWechatName?wechatName=" + role;
    }

    @GetMapping("/delete")
    public String delete(Integer id, @RequestParam("role") String role) {
        User user = userRepository.findById(id).get();
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

    private List<String> getLocations() {
        List<String> services = client.getServices();
        List<String> available_Services = new ArrayList<>();
        for (String service : services) {
            if (service.contains("campur")) {
                available_Services.add(service);
            }
        }
        return available_Services;
    }

    @GetMapping("/refreshNetwork")
    public String refreshNetwork() {
        List<User> listUsers = userRepository.findAll();
        listUsers.stream().forEach(user -> {
            tools.refreshUserNetwork(user, userRepository, restTemplate, logger);
        });
        logger.info("-----------------------------------------");
        return "redirect:/admin?page=" + this.currentPage;
    }
}
