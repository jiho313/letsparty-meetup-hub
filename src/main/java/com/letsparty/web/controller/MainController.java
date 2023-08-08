package com.letsparty.web.controller;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
public class MainController {

	@GetMapping("/")
	public String home() {
		return "page/main/home";
	}
	
	@GetMapping("/party-create")
	public String partyCreate() {
		return "page/main/party-create";
	}
}
