package com.artivisi.accountreceivable.web;

import com.artivisi.accountreceivable.service.BulkUploadService;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.multipart.MultipartFile;

@Controller
@RequestMapping("/admin/bulk-uploads")
public class BulkUploadAdminController {

    private final BulkUploadService service;

    public BulkUploadAdminController(BulkUploadService service) {
        this.service = service;
    }

    @GetMapping
    public String form() {
        return "admin/bulk-upload/form";
    }

    @PostMapping
    public String upload(@RequestParam("file") MultipartFile file, Model model) {
        if (file.isEmpty()) {
            model.addAttribute("error", "Choose a CSV file");
            return "admin/bulk-upload/form";
        }
        String filename = file.getOriginalFilename() == null ? "upload.csv" : file.getOriginalFilename();
        try {
            model.addAttribute("result", service.upload(filename, file.getBytes()));
        } catch (Exception e) {
            model.addAttribute("error", "Failed to read file: " + e.getMessage());
        }
        return "admin/bulk-upload/form";
    }
}
