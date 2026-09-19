package com.invoice.processing;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.services.lambda.LambdaClient;
import software.amazon.awssdk.services.lambda.model.InvokeRequest;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

public class WarmUpHandler implements RequestHandler<Object, String> {

    private static final LambdaClient lambda = LambdaClient.builder().build();

    @Override
    public String handleRequest(Object input, Context context) {
        List<String> targets = Arrays.stream(
                System.getenv().getOrDefault("WARM_TARGETS", "").split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toList());

        int ok = 0;
        for (String arn : targets) {
            try {
                lambda.invoke(InvokeRequest.builder()
                        .functionName(arn)
                        .payload(SdkBytes.fromUtf8String("{\"warmup\":true}"))
                        .build());
                ok++;
            } catch (Exception e) {
                context.getLogger().log("warmup failed for " + arn + ": " + e.getMessage() + "\n");
            }
        }
        return "warmed " + ok + "/" + targets.size();
    }
}