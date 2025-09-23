package org.ddcn41.ticketing_system.domain.aws;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.services.autoscaling.AutoScalingClient;
import software.amazon.awssdk.services.autoscaling.model.AutoScalingGroup;
import software.amazon.awssdk.services.autoscaling.model.DescribeAutoScalingGroupsRequest;
import software.amazon.awssdk.services.autoscaling.model.DescribeAutoScalingGroupsResponse;
import software.amazon.awssdk.services.lambda.LambdaClient;
import software.amazon.awssdk.services.lambda.model.InvokeRequest;
import software.amazon.awssdk.services.lambda.model.InvokeResponse;

import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class ScalingService {

    private final LambdaClient lambdaClient;
    private final AutoScalingClient autoScalingClient;

    @Value("${aws.lambda.asg-scaler.function-name}")
    private String lambdaFunctionName;

    private static final String ASG_NAME = "ticket-backend-asg";

    public String scaleInstances(int desiredCount) {
        log.info("Invoking Lambda function: {} with desired count: {}", lambdaFunctionName, desiredCount);

        String payload = String.format("{\"desiredCapacity\": %d}", desiredCount);

        InvokeRequest request = InvokeRequest.builder()
                .functionName(lambdaFunctionName)
                .payload(SdkBytes.fromUtf8String(payload))
                .build();

        InvokeResponse response = lambdaClient.invoke(request);
        String result = response.payload().asUtf8String();

        log.info("Lambda response: {}", result);
        return result;
    }

    public InstanceStatusResponse getInstanceStatus() {
        log.info("Getting ASG status for: {}", ASG_NAME);

        DescribeAutoScalingGroupsRequest request = DescribeAutoScalingGroupsRequest.builder()
                .autoScalingGroupNames(ASG_NAME)
                .build();

        DescribeAutoScalingGroupsResponse response = autoScalingClient.describeAutoScalingGroups(request);

        if (response.autoScalingGroups().isEmpty()) {
            throw new RuntimeException("ASG not found: " + ASG_NAME);
        }

        AutoScalingGroup asg = response.autoScalingGroups().get(0);

        List<InstanceStatusResponse.InstanceInfo> instances = asg.instances().stream()
                .map(instance -> InstanceStatusResponse.InstanceInfo.builder()
                        .instanceId(instance.instanceId())
                        .privateIp("N/A") // EC2 API 호출 필요
                        .state(instance.lifecycleState().toString())
                        .healthStatus(instance.healthStatus())
                        .build())
                .collect(Collectors.toList());

        return InstanceStatusResponse.builder()
                .asgName(asg.autoScalingGroupName())
                .currentCapacity(asg.instances().size())
                .desiredCapacity(asg.desiredCapacity())
                .minSize(asg.minSize())
                .maxSize(asg.maxSize())
                .instances(instances)
                .lastUpdated(LocalDateTime.now())
                .build();
    }
}