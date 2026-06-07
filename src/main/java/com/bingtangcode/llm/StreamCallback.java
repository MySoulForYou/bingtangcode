package com.bingtangcode.llm;

public interface StreamCallback {

    void onToken(String token);

    void onComplete();

    void onError(Exception e);
}
