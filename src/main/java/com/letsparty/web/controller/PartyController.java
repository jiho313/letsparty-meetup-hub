package com.letsparty.web.controller;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.validation.Valid;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;

import com.letsparty.dto.PartyReqDto;
import com.letsparty.security.user.LoginUser;
import com.letsparty.service.CategoryService;
import com.letsparty.service.PartyService;
import com.letsparty.service.UserPartyApplicationService;
import com.letsparty.service.UserProfileService;
import com.letsparty.util.PartyDataUtils;
import com.letsparty.vo.Party;
import com.letsparty.vo.Post;
import com.letsparty.vo.User;
import com.letsparty.vo.UserPartyApplication;
import com.letsparty.vo.UserProfile;
import com.letsparty.web.form.PartyForm;
import com.letsparty.web.form.PostForm;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Controller
@RequiredArgsConstructor
@RequestMapping("/party")
@Slf4j
public class PartyController {
	
	private final PartyService partyService;
	private final CategoryService categoryService;
	private final UserProfileService userProfileService;
	private final UserPartyApplicationService userPartyApplicationService;
	@Value("${s3.path.covers}")
	private String coversPath;
	@Value("${s3.path.profiles}")
	private String profilesPath;
	
	@PreAuthorize("isAuthenticated()")
	@GetMapping("/{partyNo}/join")
	public String joinParty(@PathVariable int partyNo, @AuthenticationPrincipal LoginUser loginUser, Model model) {
		List<UserProfile> userProfiles = userProfileService.getAllProfileByUserId(loginUser.getId());
		PartyReqDto partyReqs = partyService.getPartyReqsByNo(partyNo);
		
		model.addAttribute("userProfiles", userProfiles);
		model.addAttribute("partyReqs", partyReqs);
		
		return "/page/party/join-party";
	}
	
	@PreAuthorize("isAuthenticated()")
	@PostMapping("/{partyNo}/join")
	public String joinParty(@PathVariable int partyNo, @AuthenticationPrincipal LoginUser loginUser, @RequestParam("profileNo") int profileNo) {
		// 파티 가입 조건과 일치하면 가입을 승인한다.
		UserProfile profile = new UserProfile();
		profile.setNo(profileNo);
		if (!userPartyApplicationService.addUserPartyApplicationIfReqMet(partyNo, loginUser.getId(), profile)) {
			// TODO 가입조건과 맞지 않다는 오류메세지를 담아서 redirect 하기
			return "redirect:/party/{partyNo}?req=fail";
		};
		return "redirect:/party/{partyNo}";
	}
	
	@PreAuthorize("isAuthenticated()")
	@GetMapping("/{partyNo}/member")
	public String member(@PathVariable int partyNo, @AuthenticationPrincipal LoginUser loginUser, Model model) {
		if (!partyService.isPartyMember(loginUser.getId(), partyNo)) {
			return "redirect:/party/{partyNo}";
		}
		List<UserPartyApplication> userPartyApplications = partyService.getUserPartyApplications(partyNo, loginUser.getNo());
		model.addAttribute("users", userPartyApplications);
		model.addAttribute("partyNo", partyNo);
		model.addAttribute("profilesPath", profilesPath);
		return "page/party/member";
	}
	
	@PreAuthorize("isAuthenticated()")
	@GetMapping("/{partyNo}/setting/modify")
	public String modify(@PathVariable int partyNo, Model model, @AuthenticationPrincipal LoginUser user) {
		// 저장된 파티 기본 정보 조회
		Party savedParty = partyService.getPartyByNo(partyNo);
		
		// 수정을 시도한 유저가 파티의 리더가 아니라면 설정화면으로 리다이렉트
		if(!savedParty.getLeader().getId().equals(user.getId())) {
			return "redirect:/party/{partyNo}/setting";
		}
		
		PartyDataUtils.addBirthYearAndCategoryList(model, categoryService);
		
		// 수정폼 생성
		PartyForm partyForm = new PartyForm();
		
		partyForm.setCategoryNo(savedParty.getCategory().getNo());
		partyForm.setName(savedParty.getName());
		partyForm.setQuota(savedParty.getQuota());
		partyForm.setGender(savedParty.getName());
		if (savedParty.getDescription() != null) {
			partyForm.setDescription(savedParty.getDescription());
		}
		
		// 저장된 파티 조건 조회
		PartyReqDto partyReqs = partyService.getPartyReqsByNo(partyNo);
		partyForm.setBirthStart(partyReqs.getBirthStart());
		partyForm.setBirthEnd(partyReqs.getBirthEnd());
		partyForm.setGender(partyReqs.getGender());
		
		switch (partyReqs.getGender()) {
		case "모두":
			partyForm.setGender("A");
			break;
		case "남성":
			partyForm.setGender("M");
			break;
		case "여성":
			partyForm.setGender("F");
			break;
		}
		
		// 커버 조회
		// 클라우드 프론트 도메인과 DB에 저장된 파일이름 결합
		String savedFileName = savedParty.getFilename();
		partyForm.setSavedName(coversPath + savedFileName);

		model.addAttribute("partyForm", partyForm );
		
		return "page/party/modify";
	}
	
	// 파티 정보 수정
	@PreAuthorize("isAuthenticated()")
	@PostMapping("/{partyNo}/setting/modify") 
	public String modify(@PathVariable int partyNo, @Valid PartyForm partyForm, BindingResult error, Model model) {
		// 최소나이(birthStart)와 최대나이(birthEnd) 검증
		int birthStart = Integer.parseInt(partyForm.getBirthStart());
		int birthEnd = Integer.parseInt(partyForm.getBirthEnd());
		if (birthStart < birthEnd) {
			error.rejectValue("birthStart", null, "최소나이는 최대나이보다 적어야 합니다.");
		}
		
		// 각 태그의 글자수 검증
		List<String> tags = partyForm.getTags();
		if (tags != null && !tags.isEmpty()) {
			for (String tag : tags) {
				if (tag.length() > 20) {
					error.rejectValue("description", null, "'#" + tag + "...'태그는 20자를 초과하였습니다.");
					break;
				}
			}
		}
		
		// 유효성 검사 실패시에 수정 폼으로 돌아간다.
		if (error.hasErrors()) {
			Party savedParty = partyService.getPartyByNo(partyNo);
			String savedFileName = savedParty.getFilename();
			partyForm.setSavedName(coversPath + savedFileName);
			
			PartyDataUtils.addBirthYearAndCategoryList(model, categoryService);
			model.addAttribute("partyForm", partyForm);
			return "page/party/modify";
		}
		
		partyService.modifyParty(partyForm, partyNo);
		
		return "redirect:/party/{partyNo}/setting" ;
	}
	
	@PreAuthorize("isAuthenticated()")
	@GetMapping("/{partyNo}/post")
	public String addPost(@PathVariable int partyNo) {
		return "/page/party/post";
	}
	
	// 게시물 제출
	@PreAuthorize("isAuthenticated()")
	@PostMapping("/{partyNo}/post")
	public String addPost(@PathVariable int partyNo, @AuthenticationPrincipal LoginUser loginUser, PostForm postForm) {
		Post post = new Post();
		User user = new User();
		Party party = partyService.getPartyByNo(partyNo);
		
		user.setId(loginUser.getId());
		post.setParty(party);
		post.setUser(user);
		postForm.setPost(post);
		
		partyService.insertPost(postForm);
		return "redirect:/party/{partyNo}";
	}
	
	@GetMapping("/{partyNo}")
	public String home(@PathVariable int partyNo, Model model) {
		// partyNo를 사용하여 파티 게시물을 조회합니다.
		return "page/party/home";
	}
	
	@PreAuthorize("isAuthenticated()")
	@GetMapping("/{partyNo}/setting")
	public String setting(@PathVariable int partyNo, @AuthenticationPrincipal LoginUser loginUser, Model model) {
		UserPartyApplication upa = userPartyApplicationService.findByPartyNoAndUserId(partyNo, loginUser.getId());
		System.out.println(upa.getUserProfile().getFilename());
		model.addAttribute("upa", upa);
		return "page/party/setting";
	}
	
	@GetMapping("/{partyNo}/withdraw/{upaNo}")
	public String withdraw(@PathVariable("partyNo") int partyNo, @PathVariable("upaNo") int upaNo) {
		userPartyApplicationService.withdraw(upaNo);
		return "redirect:/party/{partyNo}";
	}
	
	// 퇴장시키기 화면
	@PreAuthorize("isAuthenticated()")
	@GetMapping("/{partyNo}/setting/member")
	public String kickOutPage(@PathVariable int partyNo, Model model, @AuthenticationPrincipal LoginUser loginUser) {
		List<UserPartyApplication> userPartyApplications = partyService.getUserPartyApplications(partyNo, loginUser.getNo());
		Party savedParty = partyService.getPartyByNo(partyNo);
		// 자신이 해당 파티의 리더가 아니라면 리다이렉트
		if(!savedParty.getLeader().getId().equals(loginUser.getId())) {
			return "redirect:/party/{partyNo}";
		}
		model.addAttribute("users", userPartyApplications);
		model.addAttribute("partyNo", partyNo);
		model.addAttribute("profilesPath", profilesPath);
		return "page/party/kick-out";
	}
	
	// 퇴장시키기
	@PostMapping("/{partyNo}/setting/kick")
	@ResponseBody
	public ResponseEntity<Map<String, Object>> kickOut(@PathVariable int partyNo,
			@RequestParam String userId /* @AuthenticationPrincipal LoginUser loginUser */) {
		Map<String, Object> response = new HashMap<>();
	    UserPartyApplication savedUserPartyApplication = userPartyApplicationService.findByPartyNoAndUserId(partyNo, userId);
	    if(savedUserPartyApplication == null) {
	        response.put("status", "error");
	        response.put("message", "유저를 찾을 수 없습니다.");
	        return ResponseEntity.badRequest().body(response);
	    }
	    //TODO 멤버 강퇴를 시도하는 유저가 리더인지, 자기 자신을 탈퇴하려고 시도하지 않는지 loginUser인자를 사용하여 추가로 검사할 수 있음
	    
	    userPartyApplicationService.update(savedUserPartyApplication);
	    response.put("status", "success");
	    response.put("message", "퇴장 처리가 완료되었습니다.");
	    return ResponseEntity.ok(response);
	}
	
	@PreAuthorize("isAuthenticated()")
	@GetMapping("/{partyNo}/attachment")
	public String attachment(@PathVariable int partyNo){
		// partyNo를 사용해서 파티첨부파일을 조회하고 작업을 수행합니다.
		return"page/party/attachment";
	}
	
	@PreAuthorize("isAuthenticated()")
	@GetMapping("/{partyNo}/album")
	public String album(@PathVariable int partyNo){
		// partyNo를 사용해서 파티첨부파일을 조회하고 작업을 수행합니다.
		return"page/party/album";
	}
	
	@PreAuthorize("isAuthenticated()")
	@GetMapping("/{partyNo}/file")
	public String file(@PathVariable int partyNo){
		// partyNo를 사용해서 파티첨부파일을 조회하고 작업을 수행합니다.
		return"page/party/file";
	}
	
	@PreAuthorize("isAuthenticated()")
	@GetMapping("/{partyNo}/poll")
	public String poll(@PathVariable int partyNo){
		// partyNo를 사용해서 파티첨부파일을 조회하고 작업을 수행합니다.
		return"page/party/poll";
	}
	
	@PreAuthorize("isAuthenticated()")
	@GetMapping("/{partyNo}/event")
	public String event(@PathVariable int partyNo) {
		// partyNo를 사용하여 파티 정보를 조회하고 작업을 수행합니다.
		return "page/party/event";
  }

}

