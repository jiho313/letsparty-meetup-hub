package com.letsparty.mapper;

import java.util.List;
import java.util.Map;

import org.apache.ibatis.annotations.Mapper;

import com.letsparty.vo.PartyTag;

@Mapper
public interface PartyTagMapper {
	
	// 파티 태그 추가
	public void insertTag(PartyTag partyTag);
	
	// 파티 태그 조회
	public PartyTag findTagByPartyNo(Map<String, Object> param);

}
