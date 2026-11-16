package com.reviewpulse.config;

import java.nio.file.Path;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.reviewpulse.search.SnapshotStore;
import com.reviewpulse.search.index.SearchIndex;
import com.reviewpulse.search.ranking.Bm25Parameters;
import com.reviewpulse.search.tokenizer.Tokenizer;

/** Wires the framework-free search engine into Spring. */
@Configuration(proxyBeanMethods = false)
public class SearchConfig {

    @Bean
    Tokenizer tokenizer() {
        return Tokenizer.DEFAULT;
    }

    @Bean
    Bm25Parameters bm25Parameters() {
        return Bm25Parameters.DEFAULT;
    }

    @Bean
    SearchIndex searchIndex(Tokenizer tokenizer) {
        return new SearchIndex(tokenizer);
    }

    @Bean
    SnapshotStore snapshotStore(ReviewPulseProperties properties, Tokenizer tokenizer) {
        return new SnapshotStore(Path.of(properties.index().snapshotDir()), tokenizer);
    }
}
